package net.itzq.datax.dto;

import lombok.Data;

/**
 * 任务实时进度
 */
@Data
public class JobProgress {

    private String logId;
    private String taskId;
    private String state;
    private Long readRecords;
    private Long writeRecords;
    private Long errorRecords;
    private Long readBytes;
    private Long recordSpeed;
    private Long byteSpeed;
    private Long startTime;
    private Long durationMs;
}
