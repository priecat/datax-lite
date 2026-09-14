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

    /**
     * identity 快路径的表体处理：按结构复制选项剥离风险定义，并抽出需延迟恢复的外键语句。
     *
     * <p>两阶段外键策略：建表语句中先剥掉 FOREIGN KEY（父表可能不在任务范围内或靠后才建），
     * 全部表建完后由执行器统一执行 {@link IdentityBody#getForeignKeyAlters()} 恢复。
     * 默认实现原样返回（无 SHOW CREATE TABLE 拷贝路径的方言，如 PG，不会走到这里）。
     */
    default IdentityBody processIdentityBody(String targetDb, String targetTable, String body,
                                             net.itzq.datax.dto.StructureOptions options) {
        return new IdentityBody(body, java.util.Collections.<String>emptyList());
    }

    /** identity 快路径表体处理结果：剥离后的表体 + 建表完成后需执行的外键恢复语句 */
    class IdentityBody {
        private final String body;
        private final List<String> foreignKeyAlters;

        public IdentityBody(String body, List<String> foreignKeyAlters) {
            this.body = body;
            this.foreignKeyAlters = foreignKeyAlters == null
                    ? java.util.Collections.<String>emptyList() : foreignKeyAlters;
        }

        public String getBody() {
            return body;
        }

        public List<String> getForeignKeyAlters() {
            return foreignKeyAlters;
        }
    }

    /**
     * 解析建表语句表体中的外键定义（约束名 -> 完整 {@code CONSTRAINT ... FOREIGN KEY ...} 定义，不含行尾逗号）。
     *
     * <p>用于"目标表已存在且带外键"场景：同步前先移除这些外键（避免 DataX 乱序写入触发 1452），
     * 数据同步完成后统一恢复。默认返回空表（无 SHOW CREATE TABLE 拷贝路径的方言不处理外键）。
     */
    default java.util.LinkedHashMap<String, String> parseForeignKeyDefs(String body) {
        return new java.util.LinkedHashMap<>();
    }

    /** 生成移除外键语句：{@code ALTER TABLE ... DROP FOREIGN KEY `约束名`} */
    default String buildDropForeignKey(String db, String table, String constraintName) {
        return "ALTER TABLE " + qualify(db, table) + " DROP FOREIGN KEY " + quote(constraintName);
    }

    /** 生成恢复外键语句：{@code ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY ...} */
    default String buildAddForeignKey(String db, String table, String constraintDef) {
        return "ALTER TABLE " + qualify(db, table) + " ADD " + constraintDef;
    }

    /**
     * 列出表上的触发器（name -> 完整 CREATE 语句）。仅 {@code Capabilities#supportsTriggers()} 为 true 的方言实现。
     *
     * <p>返回的 CREATE 语句已剥掉 {@code DEFINER=...}（执行账号成为 definer，避免跨环境迁移时
     * definer 不存在导致恢复失败）。默认返回空列表。
     */
    default List<TriggerDef> listTriggers(Connection conn, String db, String table) {
        return java.util.Collections.emptyList();
    }

    /** 生成移除触发器语句：{@code DROP TRIGGER IF EXISTS `db`.`name`} */
    default String buildDropTrigger(String db, String triggerName) {
        return "DROP TRIGGER IF EXISTS " + qualify(db, triggerName);
    }

    /**
     * 把触发器定义重定向到目标表：将定义头部的 {@code <timing> <event> ON `表`} 改写为
     * {@code ON `目标库`.`目标表`}（正文中的 ON/JOIN 不受影响，仅按触发器语法锚点匹配首处）。
     */
    default String retargetTrigger(String createSql, String targetDb, String targetTable) {
        return createSql;
    }

    /**
     * 生成自增计数器对齐语句（第三阶段）：{@code ALTER TABLE `db`.`t` AUTO_INCREMENT = n}。
     * 默认 null（方言无自增语义，调用方跳过）。MySQL 的 ALTER 只会向上调整计数器，
     * 写入显式 id 已抬升超过 n 时保持原值，天然幂等安全。
     */
    default String buildSetAutoIncrement(String targetDb, String targetTable, long value) {
        return null;
    }

    /** 触发器定义：触发器名 + 已剥 DEFINER 的完整 {@code CREATE TRIGGER} 语句 */
    class TriggerDef {
        private final String name;
        private final String createSql;

        public TriggerDef(String name, String createSql) {
            this.name = name;
            this.createSql = createSql;
        }

        public String getName() {
            return name;
        }

        public String getCreateSql() {
            return createSql;
        }
    }

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
