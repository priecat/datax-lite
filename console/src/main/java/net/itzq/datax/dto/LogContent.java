package net.itzq.datax.dto;

import lombok.Data;

import java.util.List;

/**
 * 任务日志内容（分页读取）
 */
@Data
public class LogContent {

    private String logId;
    private boolean running;
    /** 日志总行数（内存 ring 或文件的） */
    private long total;
    /** 本段起始行号（0 开始） */
    private long start;
    private List<String> lines;
}
