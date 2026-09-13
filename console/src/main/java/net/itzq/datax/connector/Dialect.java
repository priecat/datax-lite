package net.itzq.datax.connector;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

/**
 * 方言：标识符规则 + 元数据 SQL + DDL 生成。
 *
 * <p>与 {@link Metadata} 的分工：<b>Dialect 只管"SQL 长什么样"，Metadata 负责"开连接、绑参数、执行、映射结果集"</b>。
 * 这样 P1 起就能用 profile 里的 {@code dialectOverrides} 替换 SQL 而不用改代码。
 *
 * <p><b>本接口是方言的唯一权威</b> —— 插件侧 DataX 的 {@code DataBaseType} 也有
 * {@code quoteColumnName}/{@code formatPk} 等能力，但那些只服务于读写 SQL 拼装，
 * 不要在这里出现第二套方言实现（设计文档 §5.4 派生风险）。
 */
public interface Dialect {

    /** 标识符引用（带转义），如 MySQL 返回 {@code `col`}、PG 返回 {@code "col"} */
    String quote(String identifier);

    /** 限定表名，如 MySQL 返回 {@code `db`.`t`} */
    String qualify(String db, String table);

    /**
     * 列名归一化，用于跨库列名比较。
     *
     * <p>MySQL 不折叠（返回原样小写形式）、PG 折叠为小写、Oracle/达梦折叠为大写。
     * 严禁在编排层直接 {@code toLowerCase()} —— 达梦下会把 {@code T_USER} 与 {@code t_user} 误判成不同列。
     */
    String normalizeIdentifier(String name);

    /**
     * 生成建表语句（列表）。
     *
     * <p>当 {@code req.identity == true} 且 {@code req.sourceCreateTable != null} 时走
     * "源库建表语句原样拷贝"快路径；否则按列逐条生成。
     */
    List<String> buildCreateTable(DdlRequest req);

    /** 生成补列语句（列表；达梦需拆出独立的 COMMENT ON COLUMN） */
    List<String> buildAddColumn(AddColumnRequest req);

    /** 生成删表语句（列表） */
    List<String> buildDropTable(String db, String table);

    /**
     * 生成清空表语句（writer 的 preSql 用）。
     *
     * <p>单独成方法是为了消除"预览日志"与"DataX 参数"两处重复拼装 ——
     * 改造前 {@code TaskService} 与 {@code DataxJobBuilder} 各写了一遍反引号版本。
     */
    String buildTruncateTable(String db, String table);

    /**
     * 读取源表建表语句原文（{@code SHOW CREATE TABLE} 等）。
     *
     * @return 完整语句；不支持该能力的方言可返回 {@code null}
     */
    String showCreateTable(Connection conn, String db, String table);

    /**
     * 从建表语句原文中抽出"表体"部分（自第一个 {@code (} 起至末尾，并 trim）。
     *
     * <p>抽出来单独成方法是因为这是方言相关的解析（MySQL 的 SHOW CREATE TABLE 带
     * 引擎/字符集后缀，且带表名前缀）。
     */
    String extractTableBody(String rawStatement);

    // ── 元数据 SQL 模板（由 Metadata 层绑定参数执行） ──

    String sqlListDatabases();

    String sqlListTables();

    String sqlListColumns();

    String sqlTableExists();

    /** 连通性探测语句，如 MySQL 的 {@code SELECT VERSION()} */
    String sqlTestConnection();

    /** 需要从"库列表"里排除的系统库（小写比较） */
    Set<String> systemDatabases();
}
