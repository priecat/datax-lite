package net.itzq.datax.dto;

import lombok.Data;

/**
 * 任务级执行选项
 */
@Data
public class TaskOptions {

    /** 并发通道数 */
    private int channel = 3;
    /** 写入批次大小 */
    private int batchSize = 1024;
    /** insert / replace / update */
    private String writeMode = "insert";
    /** 脏数据限制方式：record（按条数）/ percentage（按比例） */
    private String errorLimitMode = "record";
    /** 允许的错误条数上限（errorLimitMode=record 时生效，0 表示零容忍） */
    private long errorLimitRecord = 0;
    /** 允许的错误比例上限（errorLimitMode=percentage 时生效，0-1） */
    private double errorLimitPercentage = 0.05;
    /** 表结构复制选项（建表 identity 快路径的剥离/恢复控制） */
    private StructureOptions structureOptions = new StructureOptions();
}
