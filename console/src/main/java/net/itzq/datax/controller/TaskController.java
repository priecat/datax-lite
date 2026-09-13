package net.itzq.datax.controller;

import net.itzq.datax.common.R;
import net.itzq.datax.dto.TaskPlanPreview;
import net.itzq.datax.entity.SyncTask;
import net.itzq.datax.entity.SyncTaskLog;
import net.itzq.datax.service.TaskLogService;
import net.itzq.datax.service.TaskService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskLogService taskLogService;

    public TaskController(TaskService taskService, TaskLogService taskLogService) {
        this.taskService = taskService;
        this.taskLogService = taskLogService;
    }

    @GetMapping
    public R<List<SyncTask>> list() {
        return R.ok(taskService.list());
    }

    @GetMapping("/{id}")
    public R<SyncTask> get(@PathVariable String id) {
        return R.ok(taskService.get(id));
    }

    /** 创建任务（config 为 TaskConfig 序列化后的 JSON 字符串） */
    @PostMapping
    public R<SyncTask> create(@RequestBody SyncTask task) {
        return R.ok(taskService.create(task));
    }

    @PutMapping("/{id}")
    public R<SyncTask> update(@PathVariable String id, @RequestBody SyncTask task) {
        return R.ok(taskService.update(id, task));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        taskService.delete(id);
        return R.ok();
    }

    /** 立即执行，返回执行记录 id */
    @PostMapping("/{id}/run")
    public R<Map<String, String>> run(@PathVariable String id) {
        String logId = taskService.run(id);
        return R.ok(Collections.singletonMap("logId", logId));
    }

    /** 执行历史 */
    @GetMapping("/{id}/logs")
    public R<List<SyncTaskLog>> logs(@PathVariable String id,
                                     @RequestParam(defaultValue = "20") int limit) {
        return R.ok(taskLogService.byTask(id, limit));
    }

    /** 预览生成的 DataX job.json */
    @GetMapping("/{id}/jobjson")
    public R<String> jobJson(@PathVariable String id) {
        return R.ok(taskService.previewJobJson(id));
    }

    /** 预览执行计划（编辑器第四步，保存前调用；只读元数据，不执行 DDL/写入） */
    @PostMapping("/preview")
    public R<TaskPlanPreview> preview(@RequestBody SyncTask task) {
        return R.ok(taskService.previewPlan(task));
    }
}
