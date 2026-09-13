package net.itzq.datax.entity;

import lombok.Data;

import java.util.Date;

/**
 * 定时任务（表结构沿用 sys_schedule，新增 task_id 关联同步任务）
 */
@Data
public class Schedule {

    public static final String STATUS_ENABLED = "1";
    public static final String STATUS_DISABLED = "0";

    private String id;
    private String name;
    private String tGroup;
    private String expression;
    private String status;
    private String isInfo;
    private String classname;
    /** 关联的同步任务 id */
    private String taskId;
    private String description;
    private String createBy;
    private Date createDate;
    private String updateBy;
    private Date updateDate;
    private String delFlag;
}
