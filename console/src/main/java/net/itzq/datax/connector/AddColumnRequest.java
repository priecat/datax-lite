package net.itzq.datax.connector;

import lombok.Data;

/**
 * 补列 DDL 的入参。
 *
 * <p>返回值是 {@code List<String>} 而不是单条语句 —— 因为部分方言（达梦 DM）的
 * {@code ALTER TABLE ADD COLUMN} 不支持内联 {@code COMMENT}，必须拆成独立的
 * {@code COMMENT ON COLUMN} 语句。MySQL 下恒为 1 条，行为与改造前一致。
 */
@Data
public class AddColumnRequest {

    private String targetDb;
    private String targetTable;

    /** 待补齐的列（目标类型已由 {@link TypeMapping#decide} 决定） */
    private ResolvedColumn column;
}
