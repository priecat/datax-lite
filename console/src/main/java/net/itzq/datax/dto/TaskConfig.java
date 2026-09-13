package net.itzq.datax.dto;

import lombok.Data;

import java.util.List;

/**
 * 同步任务映射配置（存储于 sync_task.config）
 */
@Data
public class TaskConfig {

    private List<TableMapping> tables;

    private TaskOptions options;
}
