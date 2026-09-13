package net.itzq.datax.connector.pg;

import lombok.extern.slf4j.Slf4j;
import net.itzq.datax.common.BizException;
import net.itzq.datax.connector.Metadata;
import net.itzq.datax.dto.ColumnMeta;
import net.itzq.datax.dto.TableMeta;
import net.itzq.datax.entity.DataSource;
import net.itzq.datax.jdbc.DriverConnections;
import net.itzq.datax.jdbc.JdbcDriverHolder;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 元数据读取与 JDBC 直连操作。
 *
 * <p>与 MySQL 版同构：SQL 交给 {@link PgDialect}，本类只负责开连接、绑参数、执行与结果集映射。
 * 连接锚点由 {@code defaultDb} 承担（URL 层），{@code db} 参数是 schema（查询层限定）。
 */
@Slf4j
@Component
public class PgMetadata implements Metadata {

    private final PgCapabilities capabilities;
    private final PgDialect dialect;
    private final JdbcDriverHolder driverHolder;

    public PgMetadata(PgCapabilities capabilities, PgDialect dialect, JdbcDriverHolder driverHolder) {
        this.capabilities = capabilities;
        this.dialect = dialect;
        this.driverHolder = driverHolder;
    }

    @Override
    public Connection open(DataSource ds, String db) {
        // 驱动由部署目录 jdbc/ 提供（数据源可显式指定 driverClass，缺省用品牌档案默认值）
        String url = capabilities.buildJdbcUrl(ds, db);
        String cls = ds.getDriverClass() != null && !ds.getDriverClass().trim().isEmpty()
                ? ds.getDriverClass().trim() : capabilities.driverClass();
        return DriverConnections.open(driverHolder, cls, url, ds.getUsername(), ds.getPassword());
    }

    @Override
    public void testConnection(DataSource ds) {
        try (Connection conn = open(ds, null);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(dialect.sqlTestConnection())) {
            if (rs.next()) {
                log.info("测试连接成功, 数据源[{}@{}:{}], {}", ds.getName(), ds.getHost(), ds.getPort(), rs.getString(1));
            }
        } catch (SQLException e) {
            throw new BizException("连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String serverVersion(DataSource ds) {
        try (Connection conn = open(ds, null);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(dialect.sqlTestConnection())) {
            return rs.next() ? rs.getString(1) : "";
        } catch (SQLException e) {
            throw new BizException("连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> listDatabases(DataSource ds) {
        List<String> result = new ArrayList<String>();
        try (Connection conn = open(ds, null);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(dialect.sqlListDatabases())) {
            while (rs.next()) {
                String schema = rs.getString(1);
                // 系统 schema：固定名单 + pg_ 前缀（pg_temp_* / pg_toast_* 等）
                if (!dialect.systemDatabases().contains(schema.toLowerCase())
                        && !schema.toLowerCase().startsWith("pg_")) {
                    result.add(schema);
                }
            }
        } catch (SQLException e) {
            throw new BizException("查询数据库列表失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public List<TableMeta> listTables(DataSource ds, String db) {
        List<TableMeta> result = new ArrayList<TableMeta>();
        try (Connection conn = open(ds, db);
             PreparedStatement ps = conn.prepareStatement(dialect.sqlListTables())) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableMeta t = new TableMeta();
                    t.setName(rs.getString("TABLE_NAME"));
                    t.setEngine(rs.getString("ENGINE"));
                    t.setRows(rs.getLong("TABLE_ROWS"));
                    t.setComment(rs.getString("TABLE_COMMENT"));
                    result.add(t);
                }
            }
        } catch (SQLException e) {
            throw new BizException("查询表列表失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public List<ColumnMeta> listColumns(DataSource ds, String db, String table) {
        List<ColumnMeta> result = new ArrayList<ColumnMeta>();
        try (Connection conn = open(ds, db);
             PreparedStatement ps = conn.prepareStatement(dialect.sqlListColumns())) {
            // (quote_ident(?) || '.' || quote_ident(?))::regclass —— schema, table
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnMeta c = new ColumnMeta();
                    c.setName(rs.getString("COLUMN_NAME"));
                    c.setType(rs.getString("COLUMN_TYPE"));
                    c.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                    c.setDefaultValue(rs.getString("COLUMN_DEFAULT"));
                    c.setComment(rs.getString("COLUMN_COMMENT"));
                    c.setPk("PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY")));
                    c.setOrdinal(rs.getInt("ORDINAL_POSITION"));
                    result.add(c);
                }
            }
        } catch (SQLException e) {
            throw new BizException("查询列信息失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public boolean tableExists(DataSource ds, String db, String table) {
        try (Connection conn = open(ds, db);
             PreparedStatement ps = conn.prepareStatement(dialect.sqlTableExists())) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        } catch (SQLException e) {
            throw new BizException("检查目标表是否存在失败: " + e.getMessage(), e);
        }
    }

    /** PG 没有 SHOW CREATE TABLE（nativeShowCreate=false 保证不会走到这里） */
    @Override
    public String showCreateTable(DataSource ds, String db, String table) {
        return null;
    }

    @Override
    public void executeDdl(DataSource ds, String db, String ddl) {
        try (Connection conn = open(ds, db);
             Statement st = conn.createStatement()) {
            st.execute(ddl);
            log.info("目标库执行DDL成功[{}:{}]: {}", ds.getHost(), db, ddl);
        } catch (SQLException e) {
            throw new BizException("目标库执行DDL失败: " + e.getMessage() + " | DDL: " + ddl, e);
        }
    }
}
