package net.itzq.datax.entity;

import lombok.Data;

import java.util.Date;

@Data
public class SyncTask {

    private String id;
    private String name;
    private String sourceDatasourceId;
    private String sourceDatabase;
    private String targetDatasourceId;
    private String targetDatabase;
    /** 任务映射配置 JSON（TaskConfig 结构） */
    private String config;
    /** 任务完成通知配置 JSON（NotifyRequest 数组） */
    private String notifyConfig;
    private String description;
    private String createBy;
    private Date createDate;
    private String updateBy;
    private Date updateDate;
    private String delFlag;
}
