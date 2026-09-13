package net.itzq.datax.connector;

import lombok.Data;

/**
 * 单条类型兼容性裁决结果。
 *
 * <p>P0 阶段只承载结构（MySQL 恒等映射不产出 issue）；P2 起接入
 * {@code TaskService.previewPlan}，在前端"第 4 步预览"分级展示。
 */
@Data
public class TypeIssue {

    private IssueLevel level;
    /** 表名（可为空） */
    private String table;
    /** 列名（可为空；表级问题为空） */
    private String column;
    /** 源类型原文 */
    private String sourceType;
    /** 协商后选定的目标类型 */
    private String targetType;
    /** 面向用户的说明 */
    private String message;

    public static TypeIssue of(IssueLevel level, String column, String sourceType, String targetType, String message) {
        TypeIssue issue = new TypeIssue();
        issue.level = level;
        issue.column = column;
        issue.sourceType = sourceType;
        issue.targetType = targetType;
        issue.message = message;
        return issue;
    }
}
