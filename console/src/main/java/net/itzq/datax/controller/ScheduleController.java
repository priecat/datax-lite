package net.itzq.datax.controller;

import net.itzq.datax.common.R;
import net.itzq.datax.entity.Schedule;
import net.itzq.datax.entity.SyncTaskLog;
import net.itzq.datax.service.ScheduleService;
import net.itzq.datax.service.TaskLogService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final TaskLogService taskLogService;

    public ScheduleController(ScheduleService scheduleService, TaskLogService taskLogService) {
        this.scheduleService = scheduleService;
        this.taskLogService = taskLogService;
    }

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(scheduleService.list());
    }

    @PostMapping
    public R<Schedule> create(@RequestBody Schedule schedule) {
        return R.ok(scheduleService.create(schedule));
    }

    @PutMapping("/{id}")
    public R<Schedule> update(@PathVariable String id, @RequestBody Schedule schedule) {
        return R.ok(scheduleService.update(id, schedule));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        scheduleService.delete(id);
        return R.ok();
    }

    /** 启用/停用 */
    @PostMapping("/{id}/toggle")
    public R<Schedule> toggle(@PathVariable String id, @RequestParam boolean enabled) {
        return R.ok(scheduleService.toggle(id, enabled));
    }

    /** 立即执行一次 */
    @PostMapping("/{id}/run")
    public R<Map<String, String>> runOnce(@PathVariable String id) {
        String logId = scheduleService.runOnce(id);
        return R.ok(java.util.Collections.singletonMap("logId", logId));
    }

    /** 最近执行记录 */
    @GetMapping("/{id}/logs")
    public R<List<SyncTaskLog>> logs(@PathVariable String id,
                                     @RequestParam(defaultValue = "10") int limit) {
        return R.ok(taskLogService.bySchedule(id, limit));
    }

    /** cron 校验与下次执行时间预览 */
    @GetMapping("/next-times")
    public R<List<Long>> nextTimes(@RequestParam String expression,
                                   @RequestParam(defaultValue = "5") int count) {
        return R.ok(scheduleService.nextTimes(expression, count));
    }
}
