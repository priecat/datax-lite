package net.itzq.datax.connector;

/**
 * 类型协商裁决级别。
 *
 * <p>{@code ERROR} 阻断执行（或要求用户显式指定目标类型）；
 * {@code WARN} 放行但必须在预览页醒目提示；
 * {@code INFO} 无损但需说明（例如 unsigned 语义变化）。
 */
public enum IssueLevel {

    /** 无法映射 / 必然失败 —— 阻断执行或要求逐列指定 */
    ERROR,

    /** 有损映射（精度收窄、丢时分秒、字符集不兼容）—— 放行但告警 */
    WARN,

    /** 无损但语义有变化 —— 仅说明 */
    INFO
}
