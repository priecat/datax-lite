package net.itzq.datax.service;

import net.itzq.datax.common.BizException;
import net.itzq.datax.common.IdGen;
import net.itzq.datax.config.AppProperties;
import net.itzq.datax.entity.Schedule;
import net.itzq.datax.entity.SyncTask;
import net.itzq.datax.mapper.ScheduleMapper;
import net.itzq.datax.mapper.SyncTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 基于 Spring TaskScheduler 的动态定时任务管理
 */
@Slf4j
@Service
public class ScheduleService {

    private final ScheduleMapper scheduleMapper;
    private final SyncTaskMapper taskMapper;
    private final TaskService taskService;
    private final AppProperties appProperties;

    private ThreadPoolTaskScheduler scheduler;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public ScheduleService(ScheduleMapper scheduleMapper, SyncTaskMapper taskMapper,
                           TaskService taskService, AppProperties appProperties) {
        this.scheduleMapper = scheduleMapper;
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void init() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, appProperties.getSchedulePoolSize()));
        scheduler.setThreadNamePrefix("datax-schedule-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();

        List<Schedule> all = scheduleMapper.findAll();
        int count = 0;
        for (Schedule s : all) {
            if (Schedule.STATUS_ENABLED.equals(s.getStatus())) {
                register(s);
                count++;
            }
        }
        log.info("定时任务调度初始化完成, 已启用 {} 个定时任务", count);
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Schedule s : scheduleMapper.findAll()) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", s.getId());
            row.put("name", s.getName());
            row.put("tGroup", s.getTGroup());
            row.put("expression", s.getExpression());
            row.put("status", s.getStatus());
            row.put("isInfo", s.getIsInfo());
            row.put("taskId", s.getTaskId());
            row.put("description", s.getDescription());
            row.put("createDate", s.getCreateDate());
            SyncTask task = s.getTaskId() == null ? null : taskMapper.findById(s.getTaskId());
            row.put("taskName", task == null ? null : task.getName());
            row.put("nextFireTime", Schedule.STATUS_ENABLED.equals(s.getStatus()) ? nextFireTime(s.getExpression()) : null);
            result.add(row);
        }
        return result;
    }

    public Schedule create(Schedule s) {
        validate(s);
        s.setId(IdGen.uuid());
        s.setStatus(Schedule.STATUS_ENABLED.equals(s.getStatus()) ? Schedule.STATUS_ENABLED : Schedule.STATUS_DISABLED);
        s.setCreateDate(new Date());
        s.setDelFlag("0");
        scheduleMapper.insert(s);
        if (Schedule.STATUS_ENABLED.equals(s.getStatus())) {
            register(s);
        }
        return s;
    }

    public Schedule update(String id, Schedule s) {
        validate(s);
        Schedule old = get(id);
        old.setName(s.getName());
        old.setTGroup(s.getTGroup());
        old.setExpression(s.getExpression());
        old.setStatus(Schedule.STATUS_ENABLED.equals(s.getStatus()) ? Schedule.STATUS_ENABLED : Schedule.STATUS_DISABLED);
        old.setIsInfo(s.getIsInfo());
        old.setTaskId(s.getTaskId());
        old.setDescription(s.getDescription());
        old.setUpdateDate(new Date());
        scheduleMapper.update(old);
        reschedule(old);
        return old;
    }

    public void delete(String id) {
        get(id);
        cancel(id);
        scheduleMapper.delete(id, System.currentTimeMillis());
    }

    /** 启用/停用 */
    public Schedule toggle(String id, boolean enabled) {
        Schedule old = get(id);
        old.setStatus(enabled ? Schedule.STATUS_ENABLED : Schedule.STATUS_DISABLED);
        old.setUpdateDate(new Date());
        scheduleMapper.update(old);
        reschedule(old);
        return old;
    }

    /** 立即执行一次 */
    public String runOnce(String id) {
        Schedule s = get(id);
        if (s.getTaskId() == null) {
            throw new BizException("定时任务未关联同步任务");
        }
        return taskService.run(s.getTaskId(), "schedule", s.getId());
    }

    /** 校验 cron 并返回接下来 N 次执行时间 */
    public List<Long> nextTimes(String expression, int count) {
        CronExpression cron = parseCron(expression);
        List<Long> times = new ArrayList<>();
        LocalDateTime next = LocalDateTime.now();
        for (int i = 0; i < Math.min(Math.max(1, count), 20); i++) {
            next = cron.next(next);
            if (next == null) {
                break;
            }
            times.add(next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        return times;
    }

    private void register(Schedule s) {
        if (s.getExpression() == null || s.getExpression().trim().isEmpty()) {
            return;
        }
        try {
            parseCron(s.getExpression());
            CronTrigger trigger = new CronTrigger(s.getExpression().trim());
            ScheduledFuture<?> future = scheduler.schedule(() -> execute(s.getId()), trigger);
            futures.put(s.getId(), future);
            log.info("定时任务[{}]已注册, cron={}", s.getName(), s.getExpression());
        } catch (BizException e) {
            log.error("定时任务[{}]注册失败: {}", s.getName(), e.getMessage());
        } catch (Exception e) {
            log.error("定时任务[{}]注册失败: {}", s.getName(), e.getMessage());
        }
    }

    private void reschedule(Schedule s) {
        cancel(s.getId());
        if (Schedule.STATUS_ENABLED.equals(s.getStatus())) {
            register(s);
        }
    }

    private void cancel(String scheduleId) {
        ScheduledFuture<?> f = futures.remove(scheduleId);
        if (f != null) {
            f.cancel(false);
        }
    }

    private void execute(String scheduleId) {
        Schedule s = scheduleMapper.findById(scheduleId);
        if (s == null || !Schedule.STATUS_ENABLED.equals(s.getStatus())) {
            return;
        }
        log.info("定时任务[{}]触发", s.getName());
        try {
            taskService.run(s.getTaskId(), "schedule", scheduleId);
        } catch (Exception e) {
            log.error("定时任务[{}]执行失败: {}", s.getName(), e.getMessage(), e);
        }
    }

    private CronExpression parseCron(String expression) {
        try {
            return CronExpression.parse(expression.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException("无效的cron表达式[" + expression + "]: " + e.getMessage()
                    + "（注意：使用6位cron，含秒位）");
        }
    }

    private Long nextFireTime(String expression) {
        try {
            CronExpression cron = CronExpression.parse(expression.trim());
            LocalDateTime next = cron.next(LocalDateTime.now());
            return next == null ? null : next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private Schedule get(String id) {
        Schedule s = scheduleMapper.findById(id);
        if (s == null) {
            throw new BizException("定时任务不存在");
        }
        return s;
    }

    private void validate(Schedule s) {
        if (s.getName() == null || s.getName().trim().isEmpty()) {
            throw new BizException("定时任务名称不能为空");
        }
        if (s.getTaskId() == null || s.getTaskId().trim().isEmpty()) {
            throw new BizException("请选择关联的同步任务");
        }
        parseCron(s.getExpression() == null ? "" : s.getExpression());
        if (taskMapper.findById(s.getTaskId()) == null) {
            throw new BizException("关联的同步任务不存在");
        }
    }
}
