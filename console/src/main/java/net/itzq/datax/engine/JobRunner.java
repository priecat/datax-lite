package net.itzq.datax.engine;

import com.alibaba.datax.common.element.ColumnCast;
import com.alibaba.datax.common.statistics.PerfTrace;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.common.util.MessageSource;
import com.alibaba.datax.core.statistics.communication.Communication;
import com.alibaba.datax.core.statistics.communication.CommunicationTool;
import com.alibaba.datax.core.util.ConfigParser;
import com.alibaba.datax.core.util.container.CoreConstant;
import com.alibaba.datax.core.util.container.LoadUtil;
import net.itzq.datax.common.IdGen;
import net.itzq.datax.dto.JobProgress;
import net.itzq.datax.dto.TableMapping;
import net.itzq.datax.dto.TaskConfig;
import net.itzq.datax.entity.DataSource;
import net.itzq.datax.entity.SyncTask;
import net.itzq.datax.entity.SyncTaskLog;
import net.itzq.datax.mapper.SyncTaskLogMapper;
import net.itzq.datax.service.MetaService;
import net.itzq.datax.service.NotifyService;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 单次 DataX 作业执行器：预建表 -> 生成 job.json -> 进程内运行 DataX 核心 -> 记录指标与日志。
 */
@Slf4j
public class JobRunner implements Runnable {

    private final long dataxJobId;
    private final String logId;
    private final SyncTask task;
    private final DataSource sourceDs;
    private final DataSource targetDs;
    private final TaskConfig config;
    private final DataxJobBuilder jobBuilder;
    private final MetaService metaService;
    private final TablePrepareLogic tablePrepareLogic;
    private final SyncTaskLogMapper logMapper;
    private final Path dataxHome;
    private final JobExecutor executor;
    private final NotifyService notifyService;

    private volatile ConsoleJobContainer container;
    private volatile JobProgress latestProgress = new JobProgress();
    private volatile long startMillis;
    /** run() 是否已开始执行（用于区分“运行中”与“排队中”，供停止逻辑判断） */
    private volatile boolean started;
    /** run() 是否已收尾（供 JobExecutor 兜底清理残留登记） */
    private volatile boolean finished;
    /** 最近一次「读出记录数」发生变化的时间，用于识别长时间无数据进展（卡死告警） */
    private volatile long lastProgressAt;
    private volatile long lastReadRecords = -1;
    /** 最终状态：收尾（落库/通知）期间进度接口也应返回终态，而不是停留在 RUNNING */
    private volatile String finalState;
    /** 准备阶段产出（含延迟恢复的外键动作），数据同步成功后用于第三阶段恢复外键 */
    private TablePrepareLogic.PrepareReport prepareReport;

    public JobRunner(long dataxJobId, String logId, SyncTask task, DataSource sourceDs, DataSource targetDs,
                     TaskConfig config, DataxJobBuilder jobBuilder, MetaService metaService,
                     TablePrepareLogic tablePrepareLogic, SyncTaskLogMapper logMapper, Path dataxHome, JobExecutor executor,
                     NotifyService notifyService) {
        this.dataxJobId = dataxJobId;
        this.logId = logId;
        this.task = task;
        this.sourceDs = sourceDs;
        this.targetDs = targetDs;
        this.config = config;
        this.jobBuilder = jobBuilder;
        this.metaService = metaService;
        this.tablePrepareLogic = tablePrepareLogic;
        this.logMapper = logMapper;
        this.dataxHome = dataxHome;
        this.executor = executor;
        this.notifyService = notifyService;
    }

    public String getLogId() {
        return logId;
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isFinished() {
        return finished;
    }

    /** 已执行时长（毫秒）；尚未开始执行返回 0 */
    public long elapsedMillis() {
        return startMillis <= 0 ? 0 : System.currentTimeMillis() - startMillis;
    }

    /** 距上次数据进展（读出记录数增长；从未增长则回退到开始执行）的毫秒数；尚未开始执行返回 0 */
    public long idleMillis() {
        long from = lastProgressAt > 0 ? lastProgressAt : startMillis;
        return from <= 0 ? 0 : System.currentTimeMillis() - from;
    }

    public JobProgress getProgress() {
        JobProgress p = latestProgress;
        if (finalState != null) {
            p.setState(finalState);
        } else if (p.getState() == null) {
            // 快照尚未产出：worker 还没真正开始跑就是 QUEUED，否则是 RUNNING
            p.setState(started ? SyncTaskLog.STATE_RUNNING : SyncTaskLog.STATE_QUEUED);
        }
        return p;
    }

    /** 由监控线程周期调用，刷新实时进度（累计已完成表 + 当前运行中表） */
    public void snapshotProgress() {
        ConsoleJobContainer c = container;
        if (c == null) {
            return;
        }
        try {
            Communication comm = c.snapshot();
            if (comm == null) {
                return;
            }
            long read = CommunicationTool.getTotalReadRecords(comm);
            if (read != lastReadRecords) {
                lastReadRecords = read;
                lastProgressAt = System.currentTimeMillis();
            }
            this.latestProgress = buildProgress(System.currentTimeMillis(),
                    read,
                    CommunicationTool.getTotalErrorRecords(comm),
                    CommunicationTool.getTotalReadBytes(comm));
        } catch (Throwable t) {
            log.debug("采集进度失败: {}", t.getMessage());
        }
    }

    @Override
    public void run() {
        started = true;
        Path jobsDir = dataxHome.resolve("work/jobs/" + task.getId());
        // 文件名带启动时间戳，便于 work 目录按时间排序，且 jobs/logs 通过 logId 一一对应
        String ts = IdGen.compactTime(System.currentTimeMillis());
        Path logFile = dataxHome.resolve("work/logs/" + ts + "_" + logId + ".log");
        startMillis = System.currentTimeMillis();
        String state = SyncTaskLog.STATE_FAILED;
        String message = null;

        try {
            JobLogCollector.bind(logId, logFile);
            // 立即持久化日志文件路径：运行中前端才能实时拉取日志（content 接口依赖 log_file）
            logMapper.updateLogFile(logId, logFile.toAbsolutePath().toString());

            // 排队期间可能已被停止（run() 尚未进入即被 cancel，或中断标记已置位）。
            // 此时必须放弃执行，否则会带着停止标记继续做建表/删表等准备动作；
            // Thread.interrupted() 同时清除标记，避免后续落盘、通知被中断。
            if (StopFlagRegistry.isStopped(dataxJobId) || Thread.interrupted()) {
                state = SyncTaskLog.STATE_STOPPED;
                message = "任务在开始执行前已被停止";
                logLine(message);
                return;
            }

            // 真正开始执行：QUEUED → RUNNING（准备阶段建表/生成 job.json 耗时也要计入执行中）
            logMapper.markRunning(logId);
            this.latestProgress = buildProgress(System.currentTimeMillis(), 0, 0, 0);

            logLine("开始执行任务: " + task.getName() + " [" + task.getSourceDatabase() + " -> " + task.getTargetDatabase() + "]");

            prepareTables();

            // 生成单个多表作业：内核 JobContainer 已支持多 content 逐表切分，
            // 所有表的 task 进入同一调度，共享 channel 池并发执行
            String jobJson = jobBuilder.build(sourceDs, task.getSourceDatabase(),
                    targetDs, task.getTargetDatabase(), config);
            Path jobFile = jobsDir.resolve(ts + "_" + logId + ".json");
            Files.createDirectories(jobsDir);
            Files.write(jobFile, jobJson.getBytes(StandardCharsets.UTF_8));
            int tableCount = countEnabledTables();
            logLine("作业配置文件已生成(共 " + tableCount + " 张表): " + jobFile.toAbsolutePath());

            Configuration allConf = ConfigParser.parse(jobFile.toAbsolutePath().toString());
            MessageSource.init(allConf);
            allConf.set(CoreConstant.DATAX_CORE_CONTAINER_JOB_MODE, "standalone");
            allConf.set(CoreConstant.DATAX_CORE_CONTAINER_JOB_ID, dataxJobId);
            ColumnCast.bind(allConf);
            LoadUtil.bind(allConf);
            PerfTrace perfTrace = PerfTrace.getInstance(true, dataxJobId, -1, false);
            perfTrace.setJobInfo(allConf.getConfiguration(CoreConstant.DATAX_JOB_JOBINFO), false, 0);

            ConsoleJobContainer consoleContainer = new ConsoleJobContainer(allConf);
            this.container = consoleContainer;

            logLine("DataX 作业开始运行");
            consoleContainer.start();
            // 数据同步成功后统一恢复外键（第三阶段）。被手动停止时不做恢复：
            // 表保持"有数据无外键"状态，下次执行的准备阶段会按同样逻辑先移除再恢复，天然可重入
            if (StopFlagRegistry.isStopped(dataxJobId)) {
                state = SyncTaskLog.STATE_STOPPED;
                message = "任务在数据同步完成后、恢复外键前被手动停止";
                logLine(message);
                return;
            }
            // 恢复失败抛出异常 → run() 统一置为 FAILED（数据已同步，日志会写明）
            tablePrepareLogic.restoreForeignKeys(prepareReport, true,
                    targetDs, task.getTargetDatabase(), this::logLine);
            tablePrepareLogic.restoreTriggers(prepareReport, true,
                    targetDs, task.getTargetDatabase(), this::logLine);
            tablePrepareLogic.restoreAutoIncrement(prepareReport, true,
                    targetDs, task.getTargetDatabase(), this::logLine);
            state = SyncTaskLog.STATE_SUCCESS;
            logLine("任务执行成功");
        } catch (Throwable e) {
            boolean stoppedNow = StopFlagRegistry.isStopped(dataxJobId);
            state = stoppedNow ? SyncTaskLog.STATE_STOPPED : SyncTaskLog.STATE_FAILED;
            message = rootMessage(e);
            logLine("任务执行" + (stoppedNow ? "已被手动停止" : "失败") + ": " + message);
            log.error("任务执行异常, logId={}", logId, e);
        } finally {
            // 收尾落库与通知；无论是否异常，都必须回到 release() 回收标记与资源
            finalState = state;
            try {
                SyncTaskLog finished = persistLog(state, message,
                        finalProgress(), System.currentTimeMillis(), logFile);
                // 任务结束后发送通知（等待发送完成，通知结果已写入任务日志末尾），再关闭日志
                notifyService.sendTaskFinished(task, finished);
            } catch (Throwable t) {
                log.error("任务收尾失败, logId={}", logId, t);
            } finally {
                release();
            }
        }
    }

    /**
     * 回收本次执行占用的内存标记与资源：任务日志上下文、停止标记、任务占位。
     * 必须在 finally 中调用——漏掉任一项都会在长跑进程里持续累积
     * （日志 ring 缓冲与文件句柄、停止标记、任务占位导致该任务永远无法再次执行）。
     */
    private void release() {
        finished = true;
        try {
            JobLogCollector.unbind(logId);
        } catch (Throwable t) {
            log.warn("回收任务日志上下文失败, logId={}: {}", logId, t.getMessage());
        }
        try {
            StopFlagRegistry.unregister(dataxJobId);
        } catch (Throwable t) {
            log.warn("注销停止标记失败, jobId={}: {}", dataxJobId, t.getMessage());
        }
        try {
            executor.onJobFinished(logId, task.getId());
        } catch (Throwable t) {
            log.warn("释放任务占位失败, logId={}: {}", logId, t.getMessage());
        }
    }

    private int countEnabledTables() {
        int n = 0;
        if (config.getTables() != null) {
            for (TableMapping tm : config.getTables()) {
                if (tm.isEnabled()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 任务结束时的最终指标：优先取 container 汇总（含异常中断时已完成的量） */
    private JobProgress finalProgress() {
        ConsoleJobContainer c = container;
        if (c != null) {
            try {
                Communication comm = c.snapshot();
                if (comm != null) {
                    return buildProgress(System.currentTimeMillis(),
                            CommunicationTool.getTotalReadRecords(comm),
                            CommunicationTool.getTotalErrorRecords(comm),
                            CommunicationTool.getTotalReadBytes(comm));
                }
            } catch (Throwable ignored) {
            }
        }
        return buildProgress(System.currentTimeMillis(), 0, 0, 0);
    }

    /** 由容器实时指标构建进度 */
    private JobProgress buildProgress(long nowMillis, long read, long error, long bytes) {
        JobProgress p = new JobProgress();
        p.setLogId(logId);
        p.setTaskId(task.getId());
        p.setState(SyncTaskLog.STATE_RUNNING);
        long elapsedSec = Math.max(1, (nowMillis - startMillis) / 1000);
        p.setReadRecords(read);
        p.setWriteRecords(Math.max(0, read - error));
        p.setErrorRecords(error);
        p.setReadBytes(bytes);
        p.setRecordSpeed(read / elapsedSec);
        p.setByteSpeed(bytes / elapsedSec);
        p.setStartTime(startMillis);
        p.setDurationMs(nowMillis - startMillis);
        return p;
    }

    /** 建表策略：勾选"重建表"则每次删除重建；否则自动建表（不存在时）+ 自动补齐目标表缺失字段。
     *  预览与执行共用 TablePrepareLogic.runPrepare 同一编排（此处 preview=false）：
     *  日志行与第四步预览由同一段代码产生、逐行一致。本方法只保证"表存在且无外键"，
     *  外键恢复等延迟动作由 run() 在 DataX 成功后调 restoreForeignKeys 统一执行（第三阶段），
     *  任何准备失败直接抛出（不进入数据同步阶段）。 */
    private void prepareTables() {
        net.itzq.datax.dto.StructureOptions structureOptions = config.getOptions() == null
                ? new net.itzq.datax.dto.StructureOptions()
                : net.itzq.datax.dto.StructureOptions.safe(config.getOptions().getStructureOptions());
        // 日志通过回调实时输出（与预览同一行来源）；准备失败抛出异常由 run() 统一置为 FAILED
        this.prepareReport = tablePrepareLogic.runPrepare(
                sourceDs, task.getSourceDatabase(), targetDs, task.getTargetDatabase(),
                config, structureOptions, false, this::logLine);
    }

    private SyncTaskLog persistLog(String state, String message, JobProgress progress, long endMillis, Path logFile) {
        SyncTaskLog l = new SyncTaskLog();
        try {
            l.setId(logId);
            l.setState(state);
            l.setEndTime(new java.util.Date(endMillis));
            l.setDurationMs(endMillis - startMillis);
            l.setReadRecords(progress.getReadRecords());
            l.setWriteRecords(progress.getWriteRecords());
            l.setErrorRecords(progress.getErrorRecords());
            l.setReadBytes(progress.getReadBytes());
            l.setSpeedRecord(progress.getRecordSpeed() + " rec/s");
            l.setSpeedByte(humanBytes(progress.getByteSpeed()) + "/s");
            l.setMessage(message);
            l.setLogFile(logFile.toAbsolutePath().toString());
            logMapper.updateFinish(l);
        } catch (Exception e) {
            log.error("更新执行记录失败, logId={}", logId, e);
        }
        return l;
    }

    private void logLine(String line) {
        JobLogCollector.append(logId, "[" + IdGen.formatTime(System.currentTimeMillis()) + "] [console] INFO " + line);
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = t.getClass().getName();
        }
        return msg;
    }

    private String humanBytes(Long bytes) {
        if (bytes == null || bytes < 0) {
            return "0B";
        }
        double b = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (b >= 1024 && i < units.length - 1) {
            b /= 1024;
            i++;
        }
        return String.format("%.2f%s", b, units[i]);
    }
}
