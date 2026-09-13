package net.itzq.datax.controller;

import net.itzq.datax.common.R;
import net.itzq.datax.dto.JobProgress;
import net.itzq.datax.dto.LogContent;
import net.itzq.datax.entity.SyncTaskLog;
import net.itzq.datax.service.TaskLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行记录与日志
 */
@RestController
@RequestMapping("/api/task-logs")
public class TaskLogController {

    private final TaskLogService logService;

    public TaskLogController(TaskLogService logService) {
        this.logService = logService;
    }

    @GetMapping
    public R<Map<String, Object>> page(@RequestParam(required = false) String state,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        List<SyncTaskLog> list = logService.page(state, keyword, page, size);
        long total = logService.count(state, keyword);
        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("list", list);
        return R.ok(result);
    }

    @GetMapping("/{logId}")
    public R<SyncTaskLog> get(@PathVariable String logId) {
        return R.ok(logService.get(logId));
    }

    @PostMapping("/{logId}/stop")
    public R<Boolean> stop(@PathVariable String logId) {
        return R.ok(logService.stop(logId));
    }

    @GetMapping("/{logId}/progress")
    public R<JobProgress> progress(@PathVariable String logId) {
        return R.ok(logService.progress(logId));
    }

    @GetMapping("/{logId}/content")
    public R<LogContent> content(@PathVariable String logId,
                                 @RequestParam(defaultValue = "0") long start,
                                 @RequestParam(defaultValue = "300") int max) {
        return R.ok(logService.content(logId, start, max));
    }
}
