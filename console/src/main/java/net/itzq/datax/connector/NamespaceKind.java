package net.itzq.datax.connector;

/**
 * 命名空间语义：解释任务的 database 字段到底指向什么。
 *
 * <p>MySQL 的"库"是 DATABASE（一个连接可以跨库）；PG / 达梦的"库"是 SCHEMA。
 * 该语义由 brand profile 声明，供前端与 Job 构建时解释 database 字段。
 */
public enum NamespaceKind {

    /** database.table —— MySQL / OceanBase(MySQL 模式) */
    DATABASE,

    /** schema.table（JDBC URL 需带连接锚点库）—— PostgreSQL / 达梦 DM */
    SCHEMA
}
