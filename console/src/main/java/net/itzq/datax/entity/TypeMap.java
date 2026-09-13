package net.itzq.datax.entity;

import lombok.Data;

import java.util.Date;

/**
 * 类型映射规则：按「源品牌 -> 目标品牌」方向分组。
 *
 * <p>{@code sourceType} 是源类型的<b>基名</b>（小写、去括号与修饰，如 {@code varchar}、
 * {@code timestamp without time zone}），{@code targetValue} 为目标 DDL 类型原文。
 * 系统预设（is_system=1）随 schema 脚本灌入；用户自定义规则优先级相同（以唯一索引
 * group+source_type 保证一源一目标，覆盖式调整）。
 */
@Data
public class TypeMap {

    private String id;
    /** 方向分组，如 pg->mysql / mysql->pg / pg->pg */
    private String groupName;
    private String sourceType;
    /** 目标 DDL 类型（落进建表/补列语句） */
    private String targetValue;
    /** 目标类型的展示名（可空，仅管理页参考） */
    private String targetLabel;
    private String remark;
    private Integer isSystem;
    private Date createDate;
    private Date updateDate;
    private String delFlag;
}
