package net.itzq.datax.engine;

import ch.qos.logback.classic.LoggerContext;
import net.itzq.datax.common.BizException;
import net.itzq.datax.common.IdGen;
import net.itzq.datax.config.AppProperties;
import net.itzq.datax.dto.JobProgress;
import net.itzq.datax.dto.TaskConfig;
import net.itzq.datax.entity.DataSource;
import net.itzq.datax.entity.SyncTask;
import net.itzq.datax.entity.SyncTaskLog;
import net.itzq.datax.mapper.SyncTaskLogMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.itzq.datax.service.MetaService;
import net.itzq.datax.service.NotifyService;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务执行调度：线程池 + 运行中任务管理 + 进度监控
 */
@Slf4j
@Service
public class JobExecutor {

    private final AppProperties appProperties;
    private final SyncTaskLogMapper logMapper;
    private final DataxJobBuilder jobBuilder;
    private final MetaService metaService;
    private final TablePrepareLogic tablePrepareLogic;
    private final NotifyService notifyService;

    /** 执行队列容量（提交队列积压上限） */
    private static final int QUEUE_CAPACITY = 100;

    private ThreadPoolExecutor pool;
    private ScheduledExecutorService monitor;
    private final Map<String, RunningEntry> running = new ConcurrentHashMap<>();
    /**
     * 按 taskId 原子占位，保证同一同步任务不并发执行。
     * 不变式：占位只在“已进入池/队列，等待 JobRunner.release() 归还”期间存在，
     * 因此合法上限 = 池容量 + 队列容量（见 {@link #claimLimit}），超过即为泄漏。
     * 归还路径只有两条且互斥：submit() 的 finally（未移交所有权时）与 JobRunner.release()（已移交时）。
     */
    private final Set<String> runningTasks = ConcurrentHashMap.newKeySet();
    /** runningTasks 的合法上限，用于泄漏告警 */
    private int claimLimit = QUEUE_CAPACITY + 1;
    /** 池占满告警的上次输出时间（节流用） */
    private volatile long lastPoolBusyWarnAt;

    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public JobExecutor(AppProperties appProperties, SyncTaskLogMapper logMapper,
                       DataxJobBuilder jobBuilder, MetaService metaService,
                       TablePrepareLogic tablePrepareLogic, NotifyService notifyService) {
        this.appProperties = appProperties;
        this.logMapper = logMapper;
        this.jobBuilder = jobBuilder;
        this.metaService = metaService;
        this.tablePrepareLogic = tablePrepareLogic;
        this.notifyService = notifyService;
    }

    @PostConstruct
    public void init() {
        int max = Math.max(1, appProperties.getMaxConcurrentJobs());
        claimLimit = max + QUEUE_CAPACITY;
        pool = new ThreadPoolExecutor(max, max, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY), namedFactory("datax-job-"),
                new ThreadPoolExecutor.AbortPolicy());
        monitor = new ScheduledThreadPoolExecutor(1, namedFactory("datax-monitor-"));
        monitor.scheduleAtFixedRate(this::snapshotAll, 3, 2, TimeUnit.SECONDS);

        // 挂载按任务采集日志的 Appender
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        JobLogAppender appender = new JobLogAppender();
        appender.setName("JOB_LOG_COLLECTOR");
        appender.setContext(ctx);
        appender.start();
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);

        // 应用重启后，标记中断的任务
        int fixed = logMapper.markAllRunningFailed("应用重启，任务被中断");
        if (fixed > 0) {
            log.warn("检测到 {} 条上次运行未结束的执行记录，已标记为 FAILED", fixed);
        }
        log.info("JobExecutor 初始化完成, 最大并发任务数={}", max);
    }

    @PreDestroy
    public void destroy() {
        if (monitor != null) {
            monitor.shutdownNow();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /**
     * 提交任务执行
     *
     * @return logId
     */
    public String submit(SyncTask task, DataSource sourceDs, DataSource targetDs, TaskConfig config,
                         String triggerType, String scheduleId) {
        if (running.size() >= pool.getMaximumPoolSize() && pool.getQueue().remainingCapacity() == 0) {
            throw new BizException("任务队列已满，请稍后再试");
        }
        // 原子占位：同一任务只允许一个执行流程通过；冲突时立即跳过（写入一条跳过记录并结束，不阻塞等待）
        if (!runningTasks.add(task.getId())) {
            return recordSkipped(task, triggerType, scheduleId);
        }
        // 占位成功之后的一切都必须在 try/finally 内：只要所有权没有移交给 JobRunner，
        // 无论抛出 Exception 还是 Error（如 OOM），占位与停止标记都必须归还。
        // 漏归还的后果不是“内存慢慢涨”，而是该任务被永久锁死（此后每次触发都被判为冲突跳过）+ 集合无界增长。
        String logId = IdGen.uuidShort();
        long dataxJobId = 0;
        boolean handedOff = false;
        String failMsg = null;
        try {
            SyncTaskLog taskLog = new SyncTaskLog();
            taskLog.setId(logId);
            taskLog.setTaskId(task.getId());
            taskLog.setTaskName(task.getName());
            taskLog.setTriggerType(triggerType);
            taskLog.setScheduleId(scheduleId);
            // 此刻只是提交进池队列，worker 未必立刻执行 → 先记 QUEUED，JobRunner 真正开始跑时才转 RUNNING
            taskLog.setState(SyncTaskLog.STATE_QUEUED);
            taskLog.setStartTime(new Date());
            logMapper.insert(taskLog);

            dataxJobId = IdGen.nextDataxJobId();
            StopFlagRegistry.register(dataxJobId);

            String dataxHome = System.getProperty("datax.home", System.getProperty("user.dir"));
            JobRunner runner = new JobRunner(dataxJobId, logId, task, sourceDs, targetDs, config,
                    jobBuilder, metaService, tablePrepareLogic, logMapper,
                    Paths.get(dataxHome), this, notifyService);
            Future<?> future = pool.submit(runner);
            running.put(logId, new RunningEntry(logId, task.getId(), task.getName(), dataxJobId, future, runner));
            // 所有权移交：此后由 JobRunner.release() -> onJobFinished 负责归还
            handedOff = true;
        } catch (Exception e) {
            failMsg = "提交执行失败: " + e.getMessage();
            log.error("任务[{}]提交执行失败: {}", task.getName(), failMsg, e);
            if (e instanceof BizException) {
                throw (BizException) e;
            }
            throw new BizException("提交任务失败: " + e.getMessage(), e);
        } finally {
            if (!handedOff) {
                runningTasks.remove(task.getId());
                if (dataxJobId > 0) {
                    StopFlagRegistry.unregister(dataxJobId);
                }
                markLogFinal(logId, SyncTaskLog.STATE_FAILED,
                        failMsg == null ? "提交执行失败" : failMsg);
            }
        }
        log.info("任务[{}]已提交执行, logId={}, trigger={}", task.getName(), logId, triggerType);
        return logId;
    }

    /** 任务冲突时跳过执行：写入一条 FAILED 执行记录（含日志文件）并立即结束，不等待上一轮结束 */
    private String recordSkipped(SyncTask task, String triggerType, String scheduleId) {
        String logId = IdGen.uuidShort();
        String triggerText = "schedule".equals(triggerType) ? "定时触发" : "手动执行";
        String reason = "任务正在执行中，本次" + triggerText + "已跳过（上一轮尚未结束）";
        String logFile = writeSkipLog(task, logId, reason);

        SyncTaskLog taskLog = new SyncTaskLog();
        taskLog.setId(logId);
        taskLog.setTaskId(task.getId());
        taskLog.setTaskName(task.getName());
        taskLog.setTriggerType(triggerType);
        taskLog.setScheduleId(scheduleId);
        // 本次触发从未进入执行队列 → 直接落 FAILED，避免出现无意义的 RUNNING 中间态
        taskLog.setState(SyncTaskLog.STATE_FAILED);
        taskLog.setStartTime(new Date());
        logMapper.insert(taskLog);

        markLogFinal(logId, SyncTaskLog.STATE_FAILED, reason, logFile);
        log.warn("任务[{}]正在执行中，本次{}已跳过, logId={}", task.getName(), triggerText, logId);
        return logId;
    }

    /** 为“跳过”的执行记录补一份日志文件，使跳过原因在日志弹窗中可见（写文件失败不影响主流程） */
    private String writeSkipLog(SyncTask task, String logId, String reason) {
        try {
            String dataxHome = System.getProperty("datax.home", System.getProperty("user.dir"));
            Path dir = Paths.get(dataxHome, "work", "logs");
            Files.createDirectories(dir);
            Path file = dir.resolve(IdGen.compactTime(System.currentTimeMillis()) + "_" + logId + ".log");
            String ts = IdGen.formatTime(System.currentTimeMillis());
            Files.write(file, Arrays.asList(
                    "[" + ts + "] [console] INFO 触发任务: " + task.getName(),
                    "[" + ts + "] [console] ERROR " + reason), StandardCharsets.UTF_8);
            return file.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("写入跳过记录日志文件失败, logId={}: {}", logId, e.getMessage());
            return null;
        }
    }

    /** 停止运行中的任务 */
    public boolean stop(String logId) {
        RunningEntry entry = running.get(logId);
        if (entry == null) {
            return false;
        }
        StopFlagRegistry.stop(entry.dataxJobId);
        boolean cancelled = entry.future.cancel(true);
        if (cancelled && !entry.runner.isStarted()) {
            // 任务仍在队列中、尚未开始执行，run() 不会执行，这里代为收尾，避免状态与任务占用残留
            markLogFinal(logId, SyncTaskLog.STATE_STOPPED, "任务在队列中等待执行时被停止");
            StopFlagRegistry.unregister(entry.dataxJobId);
            runningTasks.remove(entry.taskId);
            running.remove(logId);
        }
        log.info("已请求停止任务, logId={}", logId);
        return true;
    }

    public boolean isRunning(String logId) {
        return running.containsKey(logId);
    }

    public JobProgress progress(String logId, SyncTaskLog dbLog) {
        RunningEntry entry = running.get(logId);
        if (entry != null) {
            return entry.runner.getProgress();
        }
        JobProgress p = new JobProgress();
        if (dbLog != null) {
            p.setLogId(logId);
            p.setTaskId(dbLog.getTaskId());
            p.setState(dbLog.getState());
            p.setReadRecords(dbLog.getReadRecords());
            p.setWriteRecords(dbLog.getWriteRecords());
            p.setErrorRecords(dbLog.getErrorRecords());
            p.setReadBytes(dbLog.getReadBytes());
            p.setStartTime(dbLog.getStartTime() == null ? null : dbLog.getStartTime().getTime());
            p.setDurationMs(dbLog.getDurationMs());
        }
        return p;
    }

    /**
     * 任务结束：归还占位 + 摘除运行中登记。
     * 占位**按 taskId 无条件归还（幂等）**，不依赖 running 里是否还有登记——
     * 否则 worker 若在 submit 完成登记之前就跑完（极端时序），onJobFinished 取不到 entry
     * 就会漏归还占位，导致该任务被永久判为冲突、再也无法执行。
     */
    public void onJobFinished(String logId, String taskId) {
        runningTasks.remove(taskId);
        running.remove(logId);
    }

    /** 将尚未真正开始运行的执行记录标记为终态，避免残留 RUNNING 阻塞后续执行 */
    private void markLogFinal(String logId, String state, String message) {
        markLogFinal(logId, state, message, null);
    }

    private void markLogFinal(String logId, String state, String message, String logFile) {
        try {
            SyncTaskLog l = new SyncTaskLog();
            l.setId(logId);
            l.setState(state);
            l.setEndTime(new Date());
            l.setDurationMs(0L);
            l.setReadRecords(0L);
            l.setWriteRecords(0L);
            l.setErrorRecords(0L);
            l.setReadBytes(0L);
            l.setMessage(message);
            l.setLogFile(logFile);
            logMapper.updateFinish(l);
        } catch (Exception ex) {
            log.error("标记执行记录[{}]为[{}]失败: {}", logId, state, ex.getMessage());
        }
    }

    private void snapshotAll() {
        // ScheduledExecutorService 的周期任务一旦抛出未捕获异常就会被**永久取消且不产生任何日志**，
        // 因此这里必须兜住一切——进度刷新与残留登记自愈都依赖这个任务存活。
        try {
            doSnapshot();
        } catch (Throwable t) {
            log.warn("进度采集任务异常: {}", t.getMessage(), t);
        }
    }

    private void doSnapshot() {
        for (RunningEntry entry : running.values()) {
            try {
                if (entry.runner.isFinished()) {
                    // 自愈：任务已结束但登记残留（submit 登记与 worker 完成之间的极端时序）。
                    // 只清 running 登记、不碰占位，因此绝不会放宽互斥语义。
                    running.remove(entry.logId, entry);
                    continue;
                }
                entry.runner.snapshotProgress();
            } catch (Throwable t) {
                log.debug("进度采集异常: {}", t.getMessage());
            }
        }
        if (runningTasks.size() > claimLimit) {
            log.error("任务占位数量异常: {} 超过合法上限 {}，存在占位泄漏（该任务会被永久判为执行中）",
                    runningTasks.size(), claimLimit);
        }
        warnIfPoolBusy();
        warnIfStalled();
    }

    /**
     * 长跑/卡死告警（只告警、不自动停止——自动停止会中断正在写数据的作业，由人工决定）。
     * 覆盖两类「长期运行后卡死不执行」的现场：
     * ① 排队超时：执行池被占满（默认仅 3 个池位），任务迟迟轮不到执行；
     * ② 无数据进展：作业已开始但长时间读不出记录，通常是卡在阻塞 IO（慢 SQL / 等锁 / 网络黑洞）。
     * 内核调度器是 {@code while(true)} 轮询聚合状态、且 {@code StandAloneScheduler.isJobKilling()} 恒为 false，
     * 所以作业不自己结束就永远不会释放池位——只能靠这里告警 + 人工停止。
     */
    private void warnIfStalled() {
        int warnMinutes = appProperties.getJobWarnMinutes();
        if (warnMinutes <= 0) {
            return;
        }
        long threshold = warnMinutes * 60_000L;
        long now = System.currentTimeMillis();
        for (RunningEntry entry : running.values()) {
            try {
                String detail = stallDetail(entry, now, threshold);
                if (detail == null || now - entry.lastWarnAt < threshold) {
                    continue;
                }
                entry.lastWarnAt = now;
                log.error("任务[{}] logId={} {}；执行池 {}/{}，队列积压 {}，占位 {}。"
                                + "如需中止请在前端停止该任务（本告警不会自动停止任务）",
                        entry.taskName, entry.logId, detail,
                        pool.getActiveCount(), pool.getMaximumPoolSize(), pool.getQueue().size(), runningTasks.size());
            } catch (Throwable t) {
                log.debug("卡死检测异常: {}", t.getMessage());
            }
        }
    }

    /** 超过阈值时返回问题描述（含明细），正常返回 null */
    private String stallDetail(RunningEntry entry, long now, long threshold) {
        if (entry.runner.isFinished()) {
            return null;
        }
        if (!entry.runner.isStarted()) {
            long queuedMin = (now - entry.submitAt) / 60000;
            return queuedMin > threshold / 60000
                    ? "已排队 " + queuedMin + " 分钟仍未开始执行（执行池被占满）" : null;
        }
        long idleMin = entry.runner.idleMillis() / 60000;
        return idleMin > threshold / 60000
                ? "已运行 " + entry.runner.elapsedMillis() / 60000 + " 分钟，最近 " + idleMin + " 分钟无数据进展（疑似卡在阻塞 IO）"
                : null;
    }

    /**
     * 执行池被占满且仍有任务积压时告警（最多 60 秒一次）。
     * 这是"提交了但一直不执行"最典型的现场：作业在池线程里同步执行且无执行时长上限，
     * 只要有 maxConcurrentJobs 个作业卡在阻塞 IO，后续任务就只会排队、永远等不到执行。
     */
    private void warnIfPoolBusy() {
        int queued = pool.getQueue().size();
        if (queued == 0 || pool.getActiveCount() < pool.getMaximumPoolSize()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPoolBusyWarnAt < 60_000L) {
            return;
        }
        lastPoolBusyWarnAt = now;
        log.warn("执行池已占满（活跃 {}/{}），{} 个任务在队列中等待，占位数量={}。"
                        + "若长时间如此，通常是正在运行的作业卡在阻塞 IO（慢 SQL/等锁/网络），"
                        + "请检查运行中的任务并手动停止",
                pool.getActiveCount(), pool.getMaximumPoolSize(), queued, runningTasks.size());
    }

    private ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    /** 解析任务配置 JSON */
    public static TaskConfig parseConfig(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return CONFIG_MAPPER.readValue(json, TaskConfig.class);
        } catch (Exception e) {
            throw new BizException("任务配置解析失败: " + e.getMessage(), e);
        }
    }

    private static class RunningEntry {
        final String logId;
        final String taskId;
        final String taskName;
        final long dataxJobId;
        final Future<?> future;
        final JobRunner runner;
        /** 提交时间，用于判断「排队超时」 */
        final long submitAt = System.currentTimeMillis();
        /** 卡死告警的上次输出时间（节流用，监控线程写、其它线程读） */
        volatile long lastWarnAt;

        RunningEntry(String logId, String taskId, String taskName, long dataxJobId, Future<?> future, JobRunner runner) {
            this.logId = logId;
            this.taskId = taskId;
            this.taskName = taskName;
            this.dataxJobId = dataxJobId;
            this.future = future;
            this.runner = runner;
        }
    }
}
