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
            // 身份拷贝快路径：直接使用源库建表语句的表体，保留索引等完整定义
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
