package net.itzq.datax.entity;

import lombok.Data;

import java.util.Date;

/**
 * 执行记录（手动执行与定时执行共用）
 */
@Data
public class SyncTaskLog {

    public static final String STATE_RUNNING = "RUNNING";
    /** 已提交、在池队列中等待执行（尚未真正开始跑；用于区分"排队中"与"运行中"） */
    public static final String STATE_QUEUED = "QUEUED";
    public static final String STATE_SUCCESS = "SUCCESS";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_STOPPED = "STOPPED";

    private String id;
    private String taskId;
    private String taskName;
    /** manual / schedule */
    private String triggerType;
    private String scheduleId;
    private String state;
    private Date startTime;
    private Date endTime;
    private Long durationMs;
    private Long readRecords;
    private Long writeRecords;
    private Long errorRecords;
    private Long readBytes;
    private String speedRecord;
    private String speedByte;
    private String message;
    private String logFile;
}
