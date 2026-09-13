package net.itzq.datax.connector;

import lombok.Data;
import net.itzq.datax.dto.ColumnMapping;
import net.itzq.datax.dto.ColumnMeta;

/**
 * 已解析的列：把"列映射 + 源列元数据 + 协商后的目标类型"绑在一起。
 *
 * <p>引入它的目的是<b>让目标类型只有一个来源</b>：由 {@link TypeMapping#decide} 产出、
 * 由 {@link Dialect} 渲染。这样 P2 引入真正的类型协商时，只需改 decide 的实现，
 * 方言层完全不动。
 */
@Data
public class ResolvedColumn {

    /** 列映射：提供 source / target / comment */
    private ColumnMapping mapping;

    /** 源列元数据；为 null 表示源列信息缺失（方言应回退到兜底类型） */
    private ColumnMeta sourceMeta;

    /** 已协商的目标类型（MySQL 恒等映射下即源类型原文） */
    private String targetType;

    /** 是否主键（建表时需要拼 PRIMARY KEY） */
    private boolean primaryKey;

    /** 目标列名 */
    public String targetName() {
        return mapping == null ? null : mapping.getTarget();
    }

    /** 源列名 */
    public String sourceName() {
        return mapping == null ? null : mapping.getSource();
    }
}
