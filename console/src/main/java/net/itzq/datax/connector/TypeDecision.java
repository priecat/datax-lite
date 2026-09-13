package net.itzq.datax.connector;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 写侧类型决策结果：最终目标类型 + 规范化类型 + 裁决项。
 *
 * <p>P0 的 MySQL 实现是"恒等映射"——直接把源类型字符串原样回吐，
 * 因此 DDL 输出与改造前逐字节一致；issues 恒为空。
 */
@Data
public class TypeDecision {

    /** 最终选定的目标类型字符串，会直接进 DDL */
    private String targetType;

    /** 规范化类型（设计期语义分类） */
    private CanonicalType canonicalType = CanonicalType.UNKNOWN;

    /** 裁决项（INFO / WARN / ERROR） */
    private List<TypeIssue> issues = new ArrayList<>();

    public TypeDecision() {
    }

    public TypeDecision(String targetType, CanonicalType canonicalType) {
        this.targetType = targetType;
        this.canonicalType = canonicalType;
    }
}
