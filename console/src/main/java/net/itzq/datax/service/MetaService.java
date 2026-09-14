package net.itzq.datax.service;

import net.itzq.datax.connector.ConnectorRegistry;
import net.itzq.datax.connector.DbConnector;
import net.itzq.datax.connector.Dialect;
import net.itzq.datax.dto.ColumnMeta;
import net.itzq.datax.dto.TableMeta;
import net.itzq.datax.entity.DataSource;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.List;

/**
 * 元数据门面（Facade）。
 *
 * <p><b>这是跨库改造的核心手法</b>：本类保留原有的类名与全部方法签名，
 * 内部按数据源类型解析出 {@link DbConnector} 后委派给它的 {@code Metadata} 实现。
 * 这样 {@code DataSourceService} / {@code TaskService} / {@code DdlGenerator} /
 * {@code TablePrepareLogic} / {@code JobRunner} 的调用点<b>一个都不用改</b>，
 * 改动面被收在实现内部，任何中间状态都能编译并跑通存量功能。
 *
 * <p>原先写死 MySQL 的逻辑（{@code jdbc:mysql://} 拼装、{@code SHOW DATABASES}、
 * MySQL 形状的 {@code information_schema} 查询、反引号转义、系统库排除列表）
 * 已整体搬迁到 {@code net.itzq.datax.connector.mysql} 包下。
 *
 * <p>需要方言/类型映射/DataX 适配器时，请不要在这里加透传方法 ——
 * 直接注入 {@link ConnectorRegistry} 取 {@link DbConnector} 更清晰。
 */
@Service
public class MetaService {

    private final ConnectorRegistry registry;

    public MetaService(ConnectorRegistry registry) {
        this.registry = registry;
    }

    /**
     * 构建 JDBC URL。
     *
     * <p>注意：<b>已由 static 改为实例方法</b>（原静态方法无法按数据源类型分派）。
     * 存量只被 {@code DataxJobBuilder} 调用，而它现在改为向 {@code DataxAdapter} 索取参数，
     * 因此本方法保留为兼容入口。
     */
    public String buildJdbcUrl(DataSource ds, String db) {
        return registry.get(ds).capabilities().buildJdbcUrl(ds, db);
    }

    public Connection open(DataSource ds, String db) {
        return registry.get(ds).metadata().open(ds, db);
    }

    /** 测试连接，失败抛 BizException */
    public void testConnection(DataSource ds) {
        registry.get(ds).metadata().testConnection(ds);
    }

    public String serverVersion(DataSource ds) {
        return registry.get(ds).metadata().serverVersion(ds);
    }

    public List<String> listDatabases(DataSource ds) {
        return registry.get(ds).metadata().listDatabases(ds);
    }

    public List<TableMeta> listTables(DataSource ds, String db) {
        return registry.get(ds).metadata().listTables(ds, db);
    }

    public List<ColumnMeta> listColumns(DataSource ds, String db, String table) {
        return registry.get(ds).metadata().listColumns(ds, db, table);
    }

    public boolean tableExists(DataSource ds, String db, String table) {
        return registry.get(ds).metadata().tableExists(ds, db, table);
    }

    public String showCreateTable(DataSource ds, String db, String table) {
        return registry.get(ds).metadata().showCreateTable(ds, db, table);
    }

    /** 列出表上的触发器（不支持触发器的方言返回空列表） */
    public List<Dialect.TriggerDef> listTriggers(DataSource ds, String db, String table) {
        return registry.get(ds).metadata().listTriggers(ds, db, table);
    }

    /** 读表的自增计数器当前值（下一个将分配的自增 id）；方言无自增语义或表无自增列时返回 null */
    public Long readAutoIncrement(DataSource ds, String db, String table) {
        return registry.get(ds).metadata().readAutoIncrement(ds, db, table);
    }

    public void executeDdl(DataSource ds, String db, String ddl) {
        registry.get(ds).metadata().executeDdl(ds, db, ddl);
    }
}
