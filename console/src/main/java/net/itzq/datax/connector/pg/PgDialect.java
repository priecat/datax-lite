package net.itzq.datax.connector.pg;

import net.itzq.datax.connector.AddColumnRequest;
import net.itzq.datax.connector.DdlRequest;
import net.itzq.datax.connector.Dialect;
import net.itzq.datax.connector.ResolvedColumn;
import net.itzq.datax.dto.ColumnMeta;
import org.springframework.stereotype.Component;

import java.sql.Connection;
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
 * PostgreSQL 方言：双引号标识符 + information_schema/pg_catalog 元数据 SQL + DDL 生成。
 *
 * <p>与 MySQL 方言的结构差异：
 * <ul>
 *   <li>无 {@code SHOW CREATE TABLE}：{@link #showCreateTable} 返回 null，
 *       identity 快路径由 {@code nativeShowCreate=false} 保证不会被启用；</li>
 *   <li>列注释不能内联：建表与补列的注释一律拆成 {@code COMMENT ON COLUMN} 独立语句；</li>
 *   <li>无 ENGINE/CHARSET 表尾，建表语句以 {@code )} 收尾；</li>
 *   <li>标识符折叠为小写（{@link #normalizeIdentifier}），用于跨库列名比较。</li>
 * </ul>
 */
@Component
public class PgDialect implements Dialect {

    private static final String CREATE_TABLE_PREFIX = "CREATE TABLE IF NOT EXISTS ";
    private static final String COLUMN_TYPE_FALLBACK = "varchar(255)";
    private static final String COLUMN_BODY_FALLBACK = "varchar(255) NULL";

    private static final Set<String> SYSTEM_DATABASES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("information_schema", "pg_catalog", "pg_toast")));

    @Override
    public String quote(String identifier) {
        return "\"" + escape(identifier) + "\"";
    }

    @Override
    public String qualify(String db, String table) {
        return quote(db) + "." + quote(table);
    }

    /** PG 未加引号的标识符统一折叠为小写（与 MySQL 的 toLowerCase 同形，语义依据不同） */
    @Override
    public String normalizeIdentifier(String name) {
        return name == null ? null : name.toLowerCase();
    }

    @Override
    public List<String> buildCreateTable(DdlRequest req) {
        // nativeShowCreate=false，编排层不会给出 sourceCreateTable；仍防御性走逐列路径
        List<String> out = new ArrayList<String>();
        out.add(CREATE_TABLE_PREFIX + qualify(req.getTargetDb(), req.getTargetTable())
                + " " + buildBodyFromColumns(req));
        // 列注释不能内联，逐条拆 COMMENT ON COLUMN
        for (ResolvedColumn rc : req.getSelected()) {
            ColumnMeta src = rc.getSourceMeta();
            String comment = src == null ? null : src.getComment();
            if (comment != null && !comment.trim().isEmpty()) {
                out.add("COMMENT ON COLUMN " + qualify(req.getTargetDb(), req.getTargetTable())
                        + "." + quote(rc.targetName()) + " IS '" + comment.replace("'", "''") + "'");
            }
        }
        return out;
    }

    @Override
    public List<String> buildAddColumn(AddColumnRequest req) {
        ResolvedColumn rc = req.getColumn();
        String type = rc.getTargetType() == null || rc.getTargetType().trim().isEmpty()
                ? COLUMN_TYPE_FALLBACK : rc.getTargetType().trim();
        List<String> out = new ArrayList<String>();
        out.add("ALTER TABLE " + qualify(req.getTargetDb(), req.getTargetTable())
                + " ADD COLUMN " + quote(rc.targetName()) + " " + type);
        String comment = rc.getMapping() == null ? null : rc.getMapping().getComment();
        if (comment != null && !comment.trim().isEmpty()) {
            out.add("COMMENT ON COLUMN " + qualify(req.getTargetDb(), req.getTargetTable())
                    + "." + quote(rc.targetName()) + " IS '" + comment.replace("'", "''") + "'");
        }
        return out;
    }

    @Override
    public List<String> buildDropTable(String db, String table) {
        List<String> list = new ArrayList<String>(1);
        list.add("DROP TABLE IF EXISTS " + qualify(db, table));
        return list;
    }

    @Override
    public String buildTruncateTable(String db, String table) {
        return "TRUNCATE TABLE " + qualify(db, table);
    }

    /** PG 没有 SHOW CREATE TABLE —— identity 快路径被 nativeShowCreate=false 关闭，不应被调用 */
    @Override
    public String showCreateTable(Connection conn, String db, String table) {
        return null;
    }

    @Override
    public String extractTableBody(String rawStatement) {
        int idx = rawStatement.indexOf('(');
        if (idx <= 0) {
            throw new IllegalStateException("无法解析建表语句: " + rawStatement);
        }
        return rawStatement.substring(idx).trim();
    }

    // ── 元数据 SQL（db 参数 = schema，见 NamespaceKind.SCHEMA） ──

    /** 数据源"库列表"在 PG 语义下返回 schema 列表；系统 schema 由 systemDatabases + pg_ 前缀过滤 */
    @Override
    public String sqlListDatabases() {
        return "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name";
    }

    @Override
    public String sqlListTables() {
        return "SELECT TABLE_NAME, NULL AS ENGINE, NULL AS TABLE_ROWS, NULL AS TABLE_COMMENT "
                + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' "
                + "ORDER BY TABLE_NAME";
    }

    /**
     * 列元数据：走 pg_catalog 拿 format_type 精确类型、主键位与注释。
     *
     * <p>输出列名别名与 MySQL 的 information_schema.COLUMNS 对齐（COLUMN_NAME / COLUMN_TYPE /
     * IS_NULLABLE / COLUMN_DEFAULT / COLUMN_COMMENT / COLUMN_KEY / ORDINAL_POSITION），
     * 结果集映射代码可保持同构。
     */
    @Override
    public String sqlListColumns() {
        return "SELECT a.attname AS COLUMN_NAME, "
                + "format_type(a.atttypid, a.atttypmod) AS COLUMN_TYPE, "
                + "CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END AS IS_NULLABLE, "
                + "pg_get_expr(d.adbin, d.adrelid) AS COLUMN_DEFAULT, "
                + "col_description(a.attrelid, a.attnum) AS COLUMN_COMMENT, "
                + "CASE WHEN EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = a.attrelid "
                + "AND i.indisprimary AND a.attnum = ANY(i.indkey)) THEN 'PRI' ELSE '' END AS COLUMN_KEY, "
                + "a.attnum AS ORDINAL_POSITION "
                + "FROM pg_attribute a "
                + "LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum "
                + "WHERE a.attrelid = (quote_ident(?) || '.' || quote_ident(?))::regclass "
                + "AND a.attnum > 0 AND NOT a.attisdropped "
                + "ORDER BY a.attnum";
    }

    @Override
    public String sqlTableExists() {
        return "SELECT COUNT(1) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
    }

    @Override
    public String sqlTestConnection() {
        return "SELECT version()";
    }

    @Override
    public Set<String> systemDatabases() {
        return SYSTEM_DATABASES;
    }

    // ── 内部 ──

    /**
     * 按列生成表体。
     *
     * <p>与 MySQL 版的差异：注释不内联（已拆到 COMMENT ON COLUMN）；DEFAULT 的裸写判定
     * 比 MySQL 宽 —— PG 的默认值常见函数形式（{@code now()}、{@code nextval('...')}、
     * {@code CURRENT_TIMESTAMP}）必须保持表达式原样，加引号会变成字面字符串。
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
                    String dv = src.getDefaultValue().trim();
                    if (isBareExpression(dv)) {
                        sb.append(" DEFAULT ").append(dv);
                    } else {
                        sb.append(" DEFAULT '").append(dv.replace("'", "''")).append("'");
                    }
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
        sb.append(")");
        return sb.toString();
    }

    /**
     * DEFAULT 值是否应作为表达式裸写：
     * CURRENT_* 关键字、布尔/空值字面量、纯数字、以及任何函数调用形式（含括号配对）。
     */
    private static boolean isBareExpression(String dv) {
        String upper = dv.toUpperCase();
        if (upper.startsWith("CURRENT_") || "TRUE".equals(upper) || "FALSE".equals(upper)
                || "NULL".equals(upper)) {
            return true;
        }
        if (dv.matches("-?\\d+(\\.\\d+)?")) {
            return true;
        }
        // 函数调用形式：now() / nextval('seq'::regclass) / 'text'::varchar 等表达式
        return dv.contains("(") && dv.endsWith(")") || dv.contains("::");
    }

    private static String escape(String name) {
        return name == null ? null : name.replace("\"", "\"\"");
    }
}
