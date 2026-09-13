package net.itzq.datax.connector.pg;

import net.itzq.datax.common.BizException;
import net.itzq.datax.connector.Capabilities;
import net.itzq.datax.connector.FieldSpec;
import net.itzq.datax.connector.NamespaceKind;
import net.itzq.datax.entity.DataSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * PostgreSQL 能力声明。
 *
 * <p>与 MySQL 的关键差异：
 * <ul>
 *   <li>{@link NamespaceKind#SCHEMA}：任务的 database 字段指向 schema，而非 database；</li>
 *   <li>{@link #requiresAnchor()} 为 true：JDBC URL 必须携带一个真实存在的连接锚点库
 *       （数据源的 defaultDb），schema 名不进 URL；</li>
 *   <li>{@link #nativeShowCreate()} 为 false：PG 没有 {@code SHOW CREATE TABLE}，
 *       建表一律走逐列生成路径；</li>
 *   <li>写入仅支持 insert（官方 postgresqlwriter 无 writeMode 参数）。</li>
 * </ul>
 */
@Component
public class PgCapabilities implements Capabilities {

    @Override
    public String brand() {
        return "postgresql";
    }

    @Override
    public String family() {
        return "pg";
    }

    @Override
    public String displayName() {
        return "PostgreSQL";
    }

    @Override
    public int defaultPort() {
        return 5432;
    }

    @Override
    public String jdbcUrlTemplate() {
        return "jdbc:postgresql://{host}:{port}/{default_db}";
    }

    @Override
    public String driverClass() {
        return "org.postgresql.Driver";
    }

    @Override
    public NamespaceKind namespaceKind() {
        return NamespaceKind.SCHEMA;
    }

    @Override
    public boolean requiresAnchor() {
        // PG 建连必须落在某个真实库上（defaultDb），schema 在查询层限定
        return true;
    }

    @Override
    public boolean nativeShowCreate() {
        // PG 无 SHOW CREATE TABLE，建表走逐列生成
        return false;
    }

    @Override
    public boolean supportsSchema() {
        return true;
    }

    @Override
    public boolean supportsSplitPk() {
        return true;
    }

    @Override
    public boolean supportsUpsert() {
        // 官方 postgresqlwriter 无 writeMode，on conflict 写法需 P2 引入
        return false;
    }

    @Override
    public boolean supportsDdl() {
        return true;
    }

    @Override
    public boolean supportsComment() {
        // PG 支持列注释，但 ALTER ADD COLUMN 不能内联 COMMENT，需拆 COMMENT ON COLUMN 语句
        return true;
    }

    @Override
    public boolean supportsCharset() {
        // PG 库级编码，无表级/列级字符集
        return false;
    }

    @Override
    public List<String> writeModes() {
        return Collections.unmodifiableList(Arrays.asList("insert"));
    }

    @Override
    public List<FieldSpec> fields() {
        List<FieldSpec> list = new ArrayList<FieldSpec>();
        list.add(FieldSpec.text("host", "主机", true, "IP 或域名"));
        list.add(FieldSpec.port("port", "端口", defaultPort()));
        list.add(FieldSpec.text("username", "用户名"));
        list.add(FieldSpec.password("password", "密码"));
        // defaultDb 是 sys_datasource 上的具名列（default_db）：连接锚点库，schema 不进 URL
        list.add(FieldSpec.text("defaultDb", "连接库", true, "连接锚点库名，如 postgres"));
        // 注意：extraParams 是 sys_datasource 上的具名列（extra_params），不是 props 里的类型特有字段
        list.add(FieldSpec.text("extraParams", "额外参数"));
        // driverClass 下拉：选项由 MetaController 运行时注入（jdbc/ 目录扫描结果）
        list.add(FieldSpec.select("driverClass", "JDBC 驱动", driverClass(),
                new FieldSpec.Option(driverClass(), "默认驱动")));
        return list;
    }

    @Override
    public String extraParamsPlaceholder() {
        return "追加到 jdbc url 的参数，如 reWriteBatchedInserts=true";
    }

    /**
     * 构建 PostgreSQL JDBC URL。
     *
     * <p>URL 携带的是<b>连接锚点库</b>（数据源 defaultDb），不是任务里的 schema ——
     * 任务的 database 字段在 PG 语义下是 schema，由 Dialect.qualify 负责限定。
     */
    @Override
    public String buildJdbcUrl(DataSource ds, String db) {
        String anchor = ds.getDefaultDb() == null ? null : ds.getDefaultDb().trim();
        if (anchor == null || anchor.isEmpty()) {
            throw new BizException("PostgreSQL 数据源[" + ds.getName()
                    + "]未指定连接库(defaultDb)，无法建立连接（PG 必须落在某个真实库上）");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:postgresql://").append(ds.getHost()).append(":")
                .append(ds.getPort() == null ? 5432 : ds.getPort())
                .append("/").append(anchor);
        String extra = ds.getExtraParams();
        if (extra != null && !extra.trim().isEmpty()) {
            String e = extra.trim();
            if (e.startsWith("?")) {
                e = e.substring(1);
            }
            sb.append("?").append(e);
        }
        return sb.toString();
    }
}
