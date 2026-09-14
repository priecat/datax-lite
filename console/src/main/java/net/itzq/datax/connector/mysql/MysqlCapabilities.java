package net.itzq.datax.connector.mysql;

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
 * MySQL 能力声明。
 *
 * <p>{@link #buildJdbcUrl} <b>逐字符复刻</b>原 {@code MetaService.buildJdbcUrl} 的输出
 * （包括参数顺序与 {@code ?}/{@code &} 的处理），这是"job.json 逐字节不变"的前提之一。
 */
@Component
public class MysqlCapabilities implements Capabilities {

    @Override
    public String brand() {
        return "mysql";
    }

    @Override
    public String family() {
        return "mysql";
    }

    @Override
    public String displayName() {
        return "MySQL";
    }

    @Override
    public int defaultPort() {
        return 3306;
    }

    @Override
    public String jdbcUrlTemplate() {
        return "jdbc:mysql://{host}:{port}/{database}";
    }

    @Override
    public String driverClass() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public NamespaceKind namespaceKind() {
        return NamespaceKind.DATABASE;
    }

    @Override
    public boolean requiresAnchor() {
        // MySQL 建连时可以不指定库（SHOW DATABASES 即在无库连接上执行）
        return false;
    }

    @Override
    public boolean nativeShowCreate() {
        // 支持 SHOW CREATE TABLE 身份拷贝快路径
        return true;
    }

    @Override
    public boolean supportsSchema() {
        return false;
    }

    @Override
    public boolean supportsSplitPk() {
        return true;
    }

    @Override
    public boolean supportsUpsert() {
        return true;
    }

    @Override
    public boolean supportsDdl() {
        return true;
    }

    @Override
    public boolean supportsComment() {
        return true;
    }

    @Override
    public boolean supportsCharset() {
        return true;
    }

    @Override
    public boolean supportsTriggers() {
        // information_schema.TRIGGERS + SHOW CREATE TRIGGER（5.7+）
        return true;
    }

    @Override
    public List<String> writeModes() {
        return Collections.unmodifiableList(Arrays.asList("insert", "replace", "update"));
    }

    @Override
    public List<FieldSpec> fields() {
        List<FieldSpec> list = new ArrayList<FieldSpec>();
        list.add(FieldSpec.text("host", "主机", true, "IP 或域名"));
        list.add(FieldSpec.port("port", "端口", defaultPort()));
        list.add(FieldSpec.text("username", "用户名"));
        list.add(FieldSpec.password("password", "密码"));
        // 注意：extraParams 是 sys_datasource 上的具名列（extra_params），不是 props 里的类型特有字段
        list.add(FieldSpec.text("extraParams", "额外参数"));
        // driverClass 下拉：选项由 MetaController 运行时注入（jdbc/ 目录扫描结果）
        list.add(FieldSpec.select("driverClass", "JDBC 驱动", driverClass(),
                new FieldSpec.Option(driverClass(), "默认驱动")));
        return list;
    }

    @Override
    public String extraParamsPlaceholder() {
        return "追加到 jdbc url 的参数，如 useCompression=true";
    }

    /**
     * 构建 MySQL JDBC URL。
     *
     * <p>⚠️ 本方法是从 {@code MetaService.buildJdbcUrl} 原样搬迁的，<b>修改会破坏存量任务</b>。
     */
    @Override
    public String buildJdbcUrl(DataSource ds, String db) {
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:mysql://").append(ds.getHost()).append(":").append(ds.getPort() == null ? 3306 : ds.getPort())
                .append("/").append(db == null ? "" : db)
                .append("?useUnicode=true&characterEncoding=utf8")
                .append("&useSSL=false&allowPublicKeyRetrieval=true")
                .append("&serverTimezone=Asia/Shanghai")
                .append("&connectTimeout=10000&socketTimeout=3600000")
                .append("&zeroDateTimeBehavior=convertToNull&tinyInt1isBit=false&yearIsDateType=false")
                .append("&rewriteBatchedStatements=true");
        String extra = ds.getExtraParams();
        if (extra != null && !extra.trim().isEmpty()) {
            String e = extra.trim();
            if (e.startsWith("?")) {
                e = e.substring(1);
            }
            sb.append("&").append(e);
        }
        return sb.toString();
    }
}
