package net.itzq.datax.dto;

import lombok.Data;

/**
 * 列映射（按列名对齐，支持重命名与选择性同步）
 */
@Data
public class ColumnMapping {

    private String source;
    private String sourceType;
    private String target;
    /**
     * 用户显式指定的目标类型（字段映射对话框选择/输入；留空 = 走「类型字典 -> 恒等回退」裁决）。
     * 类型裁决优先级：显式 targetType > sys_type_map 字典规则 > 恒等回退。
     */
    private String targetType;
    /**
     * 显式类型的长度/精度部分（如 {@code 10,2}、{@code 255}），仅当 targetType 非空
     * 且 targetType 本身不含括号时合成 {@code numeric(10,2)} 这类最终 DDL 类型。
     */
    private String targetTypeLen;
    /** 源列注释（自动补齐缺失字段时用于 ALTER ADD COLUMN COMMENT，执行时由源表元数据填充） */
    private String comment;
    private boolean selected = true;
    private boolean pk;
}
