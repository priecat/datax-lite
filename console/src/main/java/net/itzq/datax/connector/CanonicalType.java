package net.itzq.datax.connector;

/**
 * 设计期规范类型。
 *
 * <p><b>只用于设计期</b>（DDL 生成、类型协商、兼容性裁决），<b>不参与运行时传输</b>。
 * 运行期继续复用 DataX 的 {@code com.alibaba.datax.common.element.Column}，不另造一套。
 *
 * <p>为什么需要它：DataX 的 {@code Column.Type} 只有 9 个值
 * （{@code BAD, NULL, INT, LONG, DOUBLE, STRING, BOOL, DATE, BYTES}），
 * 没有 DECIMAL、且把 date/time/timestamp/timestamptz 全混进 DATE 一桶。
 * 这个粒度不足以生成 DDL，也表达不了"这列原本是 time 还是 timestamptz"。
 *
 * <p>{@code CanonicalType} 与 {@code Column} 是<b>多对一</b>关系，映射表固定，
 * 多对一的那些格子就是类型协商必须出 WARN 的位置（详见设计文档 §4.3.3）。
 */
public enum CanonicalType {

    /** 整型（带 unsigned 属性）—— 运行期 LongColumn */
    INTEGER,

    /** 精确小数（带 precision / scale）—— 运行期 DoubleColumn（值不丢，见设计文档附录 A7） */
    DECIMAL,

    /** 浮点 —— 运行期 DoubleColumn */
    FLOAT,

    /** 布尔 —— 运行期 BoolColumn；注意 MySQL tinyint(1) 会落到 LongColumn，二者不可区分 */
    BOOLEAN,

    /** 日期（无时间）—— 运行期 DateColumn */
    DATE,

    /** 时间（无日期）—— 运行期 DateColumn，目标无 time 类型时会丢时分秒 */
    TIME,

    /** 日期时间（无时区）—— 运行期 DateColumn */
    DATETIME,

    /** 带时区的时间戳 —— 运行期 DateColumn，时区语义在传输中丢失 */
    TIMESTAMP_TZ,

    /** 变长字符串（带 length / charset）—— 运行期 StringColumn */
    STRING,

    /** 长文本 —— 运行期 StringColumn */
    TEXT,

    /** JSON —— 运行期 StringColumn，与 TEXT 不可区分 */
    JSON,

    /** UUID —— 运行期 StringColumn，长度约束 36 */
    UUID,

    /** 定长二进制 —— 运行期 BytesColumn */
    BINARY,

    /** 大对象二进制 —— 运行期 BytesColumn */
    BLOB,

    /** 数组（带 elementType）—— 运行期 StringColumn，目标无数组类型时需降级或拒绝 */
    ARRAY,

    /** 无法归类（兜底按文本处理，并出 WARN） */
    UNKNOWN
}
