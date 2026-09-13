package net.itzq.datax.dto;

import lombok.Data;

import java.util.List;

/**
 * 单表映射
 */
@Data
public class TableMapping {

    private String sourceTable;
    private String targetTable;
    private boolean enabled = true;
    /** 目标表不存在时自动建表 */
    private boolean autoCreateTable;
    /** 勾选后每次执行前删除目标表并按源结构重建 */
    private boolean recreateTable;
    /** 同步前清空目标表（通过 writer 的 preSql 实现，跨库由 Dialect 产出语句） */
    private boolean truncateBefore;
    /** 读取过滤条件（不含 where 关键字） */
    private String where;
    /** 切分键（提升并发），默认取主键 */
    private String splitPk;
    private List<ColumnMapping> columns;
}
