package net.itzq.datax.connector.mysql;

import net.itzq.datax.common.BizException;
import net.itzq.datax.connector.AddColumnRequest;
import net.itzq.datax.connector.DdlRequest;
import net.itzq.datax.connector.Dialect;
import net.itzq.datax.connector.ResolvedColumn;
import net.itzq.datax.dto.ColumnMapping;
import net.itzq.datax.dto.ColumnMeta;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MySQL 方言。
 *
 * <p><b>本类的所有字符串拼装都是从原 {@code DdlGenerator} / {@code TablePrepareLogic} /
 * {@code MetaService} 原样搬迁的</b>，目的是让 P0 阶段的 DDL 与元数据输出与改造前逐字节一致。
 * 修改任一拼接顺序都会破坏回归基线。
 */
@Component
public class MysqlDialect implements Dialect {

    private static final String CREATE_TABLE_PREFIX = "CREATE TABLE IF NOT EXISTS ";
    private static final String COLUMN_TYPE_FALLBACK = "varchar(255)";
    private static final String COLUMN_BODY_FALLBACK = "varchar(255) NULL";
    private static final String TABLE_TAIL = ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final Set<String> SYSTEM_DATABASES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("information_schema", "mysql", "performance_schema", "sys")));

    @Override
    public String quote(String identifier) {
        return "`" + escape(identifier) + "`";
    }

    @Override
    public String qualify(String db, String table) {
        return quote(db) + "." + quote(table);
    }

    /**
     * MySQL 的列名比较沿用改造前的 {@code toLowerCase()} 行为（MySQL 自身不折叠大小写）。
     *
     * <p>注意：达梦/Oracle 必须改成大写折叠，否则会把 {@code T_USER} 与 {@code t_user}
     * 误判成不同列并重复 ALTER。这个接缝就是为它们准备的。
     */
    @Override
    public String normalizeIdentifier(String name) {
        return name == null ? null : name.toLowerCase();
    }

    @Override
    public List<String> buildCreateTable(DdlRequest req) {
        String body;
        if (req.isIdentity() && req.getSourceCreateTable() != null) {
            // 身份拷贝快路径：表体已在 DdlGenerator 经 processIdentityBody 按选项剥离，此处纯渲染
            body = req.getSourceCreateTable();
        } else {
            body = buildBodyFromColumns(req);
        }
        return one(CREATE_TABLE_PREFIX + qualify(req.getTargetDb(), req.getTargetTable()) + " " + body);
    }

    @Override
    public List<String> buildAddColumn(AddColumnRequest req) {
        ResolvedColumn rc = req.getColumn();
        ColumnMapping m = rc.getMapping();
        StringBuilder ddl = new StringBuilder("ALTER TABLE ")
                .append(qualify(req.getTargetDb(), req.getTargetTable()))
                .append(" ADD COLUMN ").append(quote(rc.targetName())).append(" ");
        if (rc.getTargetType() != null && !rc.getTargetType().trim().isEmpty()) {
            ddl.append(rc.getTargetType().trim());
        } else {
            ddl.append(COLUMN_TYPE_FALLBACK);
        }
        // 注意：补列路径的注释取的是<b>映射</b>上的注释（建表路径取源元数据），保持与原实现一致
        String comment = m == null ? null : m.getComment();
        if (comment != null && !comment.trim().isEmpty()) {
            ddl.append(" COMMENT '").append(comment.replace("'", "''")).append("'");
        }
        return one(ddl.toString());
    }

    @Override
    public List<String> buildDropTable(String db, String table) {
        return one("DROP TABLE IF EXISTS " + qualify(db, table));
    }

    @Override
    public String buildTruncateTable(String db, String table) {
        return "TRUNCATE TABLE " + qualify(db, table);
    }

    @Override
    public String showCreateTable(Connection conn, String db, String table) {
        String sql = "SHOW CREATE TABLE " + qualify(db, table);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString(2);
            }
            throw new BizException("未找到表: " + db + "." + table);
        } catch (SQLException e) {
            throw new BizException("读取建表语句失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String extractTableBody(String rawStatement) {
        int idx = rawStatement.indexOf('(');
        if (idx <= 0) {
            throw new IllegalStateException("无法解析建表语句: " + rawStatement);
        }
        return rawStatement.substring(idx).trim();
    }

    // ── 元数据 SQL（原样搬迁自 MetaService） ──

    @Override
    public String sqlListDatabases() {
        return "SHOW DATABASES";
    }

    @Override
    public String sqlListTables() {
        return "SELECT TABLE_NAME, ENGINE, TABLE_ROWS, TABLE_COMMENT FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
    }

    @Override
    public String sqlListColumns() {
        return "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT, COLUMN_KEY, ORDINAL_POSITION "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
    }

    @Override
    public String sqlTableExists() {
        return "SELECT COUNT(1) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
    }

    @Override
    public String sqlTestConnection() {
        return "SELECT VERSION()";
    }

    @Override
    public Set<String> systemDatabases() {
        return SYSTEM_DATABASES;
    }

    // ── 内部 ──

    /** 索引定义行：KEY / UNIQUE KEY / FULLTEXT KEY / SPATIAL KEY（PRIMARY KEY 不匹配，始终保留） */
    private static final java.util.regex.Pattern INDEX_LINE =
            java.util.regex.Pattern.compile("^(UNIQUE |FULLTEXT |SPATIAL )?KEY\\s");

    /** 外键定义行：CONSTRAINT xxx FOREIGN KEY (...) */
    private static boolean isForeignKeyLine(String upperTrimmed) {
        return upperTrimmed.startsWith("CONSTRAINT") && upperTrimmed.contains("FOREIGN KEY");
    }

    /** 剥掉行尾逗号（原文行后面可能还有 KEY 等行，直拼 ALTER 会带出语法错误的尾逗号） */
    private static String stripTrailingComma(String line) {
        return line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
    }

    @Override
    public Dialect.IdentityBody processIdentityBody(String targetDb, String targetTable, String body,
                                                    net.itzq.datax.dto.StructureOptions options) {
        net.itzq.datax.dto.StructureOptions o = net.itzq.datax.dto.StructureOptions.safe(options);
        List<String> foreignKeyAlters = new ArrayList<String>();
        List<String> kept = new ArrayList<String>();
        for (String line : body.split("\n", -1)) {
            String trimmed = line.trim();
            String upper = trimmed.toUpperCase();
            // 外键：抽出为延迟恢复语句（数据同步期间目标表上不能有外键，否则 DataX 多线程乱序写入会触发 1452），
            // 关闭选项则直接丢弃
            if (isForeignKeyLine(upper)) {
                if (o.isForeignKey()) {
                    foreignKeyAlters.add(buildAddForeignKey(targetDb, targetTable, stripTrailingComma(trimmed)));
                }
                continue;
            }
            if (!o.isCheck() && upper.startsWith("CONSTRAINT") && upper.contains("CHECK")) {
                continue;
            }
            if (!o.isIndex() && INDEX_LINE.matcher(upper).matches()) {
                continue;
            }
            kept.add(line);
        }
        String result = String.join("\n", kept);
        if (!o.isEngine()) {
            result = result.replaceAll("(?i)\\s+ENGINE=[A-Za-z0-9]+", "");
        }
        // 自增计数器起始值是"状态"而非"结构"：建表语句一律不携带（含 truncate 重置等场景），
        // 统一由第三阶段 ALTER TABLE ... AUTO_INCREMENT=n 对齐（autoIncrement 开关控制）
        result = result.replaceAll("(?i)\\s+AUTO_INCREMENT=\\d+", "");
        if (!o.isCharset()) {
            result = result.replaceAll("(?i)\\s+DEFAULT CHARSET=[A-Za-z0-9_]+(\\s+COLLATE=[A-Za-z0-9_]+)?", "");
        }
        // 被剥离的行常是最后一条列定义，修复悬空逗号："...,\n)" → "...)"
        result = result.replaceAll(",(\\s*\\n\\s*\\))", "$1");
        return new Dialect.IdentityBody(result, foreignKeyAlters);
    }

    /** 约束名提取：CONSTRAINT `name` FOREIGN KEY ... / CONSTRAINT name FOREIGN KEY ... */
    private static final java.util.regex.Pattern CONSTRAINT_NAME =
            java.util.regex.Pattern.compile("^CONSTRAINT\\s+`?([^`\\s]+)`?\\s+FOREIGN KEY",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    @Override
    public java.util.LinkedHashMap<String, String> parseForeignKeyDefs(String body) {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        for (String line : body.split("\n", -1)) {
            String trimmed = line.trim();
            if (isForeignKeyLine(trimmed.toUpperCase())) {
                String def = stripTrailingComma(trimmed);
                java.util.regex.Matcher m = CONSTRAINT_NAME.matcher(def);
                if (m.find()) {
                    result.put(m.group(1), def);
                }
            }
        }
        return result;
    }

    // ── 触发器 ──

    /**
     * 触发器定义头部的表引用锚点：BEFORE/AFTER INSERT|UPDATE|DELETE ON `表`（正文中的 ON/JOIN 不会命中）。
     * 注意 SHOW CREATE TRIGGER 的输出<b>不保证标识符带反引号</b>（与 SHOW CREATE TABLE 不同），两种形式都要匹配。
     */
    private static final java.util.regex.Pattern TRIGGER_ON =
            java.util.regex.Pattern.compile("(?:BEFORE|AFTER)\\s+(?:INSERT|UPDATE|DELETE)\\s+ON\\s+(?:`[^`]+`|\\w+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** CREATE 后的 DEFINER=`user`@`host` 子句（跨环境迁移时 definer 可能不存在，统一剥掉） */
    private static final java.util.regex.Pattern TRIGGER_DEFINER =
            java.util.regex.Pattern.compile("^CREATE\\s+DEFINER=\\S+\\s+",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    @Override
    public List<Dialect.TriggerDef> listTriggers(Connection conn, String db, String table) {
        // 1. 列出该表挂的触发器名（information_schema 走参数绑定，避免 LIKE 模式转义问题）
        List<String> names = new ArrayList<String>();
        String nameSql = "SELECT TRIGGER_NAME FROM information_schema.TRIGGERS "
                + "WHERE TRIGGER_SCHEMA = ? AND EVENT_OBJECT_TABLE = ? ORDER BY TRIGGER_NAME";
        try (PreparedStatement ps = conn.prepareStatement(nameSql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new BizException("读取触发器列表失败: " + e.getMessage(), e);
        }
        if (names.isEmpty()) {
            return Collections.emptyList();
        }
        // 2. SHOW CREATE TRIGGER 取完整定义（列 3 为 "SQL Original Statement"）
        List<Dialect.TriggerDef> result = new ArrayList<Dialect.TriggerDef>();
        for (String name : names) {
            String sql = "SHOW CREATE TRIGGER " + qualify(db, name);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                if (!rs.next()) {
                    continue;
                }
                String create = rs.getString(3);
                if (create != null) {
                    // 剥 DEFINER，执行账号即 definer
                    create = TRIGGER_DEFINER.matcher(create.trim()).replaceFirst("CREATE ");
                    result.add(new Dialect.TriggerDef(name, create));
                }
            } catch (SQLException e) {
                throw new BizException("读取触发器[" + name + "]定义失败: " + e.getMessage(), e);
            }
        }
        return result;
    }

    /**
     * 把触发器定义重定向到目标表：将定义头部的 {@code <timing> <event> ON `表`} 改写为
     * {@code ON `目标表`}（正文中的 ON/JOIN 不受影响，仅按触发器语法锚点匹配首处）。
     *
     * <p>注意 ON 子句用<b>裸表名</b>：executeDdl/listTriggers 的连接已锚定目标库（open(ds, db)），
     * 裸名必然解析到目标库，也规避 CREATE TRIGGER 对 ON 表名带库名限定的兼容性风险。
     */
    @Override
    public String retargetTrigger(String createSql, String targetDb, String targetTable) {
        java.util.regex.Matcher m = TRIGGER_ON.matcher(createSql);
        if (!m.find()) {
            return createSql;
        }
        // 只改写定义头部这一处表引用（无论原文带不带反引号，统一改为引用的目标表名），正文不受影响
        return createSql.substring(0, m.start()) + m.group().replaceFirst("(`[^`]+`|\\w+)$",
                java.util.regex.Matcher.quoteReplacement(quote(targetTable))) + createSql.substring(m.end());
    }

    /** 自增计数器对齐语句（第三阶段）。n 来自源表准备阶段快照；只向上调整，幂等安全 */
    @Override
    public String buildSetAutoIncrement(String targetDb, String targetTable, long value) {
        return "ALTER TABLE " + qualify(targetDb, targetTable) + " AUTO_INCREMENT = " + value;
    }

    /**
     * 按列生成表体（原样搬迁自 {@code DdlGenerator.fromColumns}）。
     *
     * <p>注意三处容易改错的地方：
     * 1. 逗号判断用的是"当前已收集到的主键集合" {@code pks}，因此最后一列若是主键会带尾逗号；
     * 2. 列注释取的是<b>源列元数据</b>的注释（补列路径才用映射上的注释），两者不同源；
     * 3. 源列元数据缺失时一律退化为 {@code varchar(255) NULL}，忽略已协商的目标类型。
     */
    private String buildBodyFromColumns(DdlRequest req) {
        List<ResolvedColumn> selected = req.getSelected();

        StringBuilder sb = new StringBuilder();
        sb.append("(\n");
        List<String> pks = new ArrayList<String>();
        for (int i = 0; i < selected.size(); i++) {
            ResolvedColumn rc = selected.get(i);
            ColumnMeta src = rc.getSourceMeta();
            sb.append("  ").append(quote(rc.targetName())).append(" ");
            if (src != null) {
                sb.append(rc.getTargetType());
                if (!src.isNullable()) {
                    sb.append(" NOT NULL");
                }
                if (src.getDefaultValue() != null) {
                    String dv = src.getDefaultValue();
                    if ("CURRENT_TIMESTAMP".equalsIgnoreCase(dv) || dv.matches("-?\\d+")) {
                        sb.append(" DEFAULT ").append(dv);
                    } else {
                        sb.append(" DEFAULT '").append(dv.replace("'", "''")).append("'");
                    }
                }
                if (src.getComment() != null && !src.getComment().isEmpty()) {
                    sb.append(" COMMENT '").append(src.getComment().replace("'", "''")).append("'");
                }
                if (rc.isPrimaryKey()) {
                    pks.add(quote(rc.targetName()));
                }
            } else {
                // 无源元数据（源列已删/元数据缺失）：仍使用裁决出的 targetType；缺失时退化为默认列体
                String tt = rc.getTargetType() == null ? "" : rc.getTargetType().trim();
                sb.append(tt.isEmpty() ? COLUMN_BODY_FALLBACK : tt + " NULL");
            }
            if (i < selected.size() - 1 || !pks.isEmpty()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        if (!pks.isEmpty()) {
            sb.append("  PRIMARY KEY (").append(String.join(", ", pks)).append(")\n");
        }
        sb.append(TABLE_TAIL);
        return sb.toString();
    }

    private static String escape(String name) {
        return name == null ? null : name.replace("`", "``");
    }

    private static List<String> one(String statement) {
        List<String> list = new ArrayList<String>(1);
        list.add(statement);
        return list;
    }
}
