package net.itzq.datax.service;

import net.itzq.datax.common.BizException;
import net.itzq.datax.dto.JobProgress;
import net.itzq.datax.dto.LogContent;
import net.itzq.datax.engine.JobExecutor;
import net.itzq.datax.engine.JobLogCollector;
import net.itzq.datax.entity.SyncTaskLog;
import net.itzq.datax.mapper.SyncTaskLogMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.List;

@Service
public class TaskLogService {

    private final SyncTaskLogMapper logMapper;
    private final JobExecutor jobExecutor;

    public TaskLogService(SyncTaskLogMapper logMapper, JobExecutor jobExecutor) {
        this.logMapper = logMapper;
        this.jobExecutor = jobExecutor;
    }

    public List<SyncTaskLog> page(String state, String keyword, int page, int size) {
        page = Math.max(1, page);
        size = Math.min(Math.max(1, size), 200);
        return logMapper.listPage(state, keyword, size, (page - 1) * size);
    }

    public long count(String state, String keyword) {
        return logMapper.countPage(state, keyword);
    }

    public List<SyncTaskLog> byTask(String taskId, int limit) {
        return logMapper.listByTask(taskId, Math.min(Math.max(1, limit), 200));
    }

    public List<SyncTaskLog> bySchedule(String scheduleId, int limit) {
        return logMapper.listBySchedule(scheduleId, Math.min(Math.max(1, limit), 200));
    }

    public SyncTaskLog get(String logId) {
        SyncTaskLog l = logMapper.findById(logId);
        if (l == null) {
            throw new BizException("执行记录不存在");
        }
        return l;
    }

    public JobProgress progress(String logId) {
        SyncTaskLog l = get(logId);
        return jobExecutor.progress(logId, l);
    }

    public LogContent content(String logId, long start, int max) {
        SyncTaskLog l = get(logId);
        java.nio.file.Path logFile = l.getLogFile() == null ? null : Paths.get(l.getLogFile());
        boolean running = jobExecutor.isRunning(logId);
        LogContent content = new LogContent();
        content.setLogId(logId);
        content.setRunning(running);
        if (logFile == null) {
            content.setTotal(0);
            content.setStart(0);
            content.setLines(java.util.Collections.emptyList());
            return content;
        }
        if (running || JobLogCollector.isRunning(logId)) {
            JobLogCollector.LogSegment seg = JobLogCollector.read(logId, logFile, start, Math.min(max, 500));
            content.setTotal(seg.total);
            content.setStart(seg.start);
            content.setLines(seg.lines);
        } else {
            JobLogCollector.LogSegment seg = JobLogCollector.read(logId, logFile, start, Math.min(max, 500));
            content.setTotal(seg.total);
            content.setStart(seg.start);
            content.setLines(seg.lines);
        }
        return content;
    }

    public boolean stop(String logId) {
        get(logId);
        boolean stopped = jobExecutor.stop(logId);
        if (!stopped) {
            throw new BizException("任务不在运行中");
        }
        return true;
    }
}
