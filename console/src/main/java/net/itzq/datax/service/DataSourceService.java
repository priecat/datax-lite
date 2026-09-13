package net.itzq.datax.service;

import net.itzq.datax.common.BizException;
import net.itzq.datax.common.IdGen;
import net.itzq.datax.connector.Capabilities;
import net.itzq.datax.connector.ConnectorRegistry;
import net.itzq.datax.dto.ColumnMeta;
import net.itzq.datax.dto.TableMeta;
import net.itzq.datax.entity.DataSource;
import net.itzq.datax.mapper.DataSourceMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DataSourceService {

    private static final String DEFAULT_TYPE = "mysql";

    private final DataSourceMapper mapper;
    private final MetaService metaService;
    private final ConnectorRegistry registry;

    public DataSourceService(DataSourceMapper mapper, MetaService metaService, ConnectorRegistry registry) {
        this.mapper = mapper;
        this.metaService = metaService;
        this.registry = registry;
    }

    public List<DataSource> list() {
        return mapper.findAll();
    }

    public DataSource get(String id) {
        DataSource ds = mapper.findById(id);
        if (ds == null) {
            throw new BizException("数据源不存在");
        }
        return ds;
    }

    public DataSource create(DataSource ds) {
        // 尊重入参 type（原先被无条件覆盖为 mysql），仅在校验后落库
        normalizeType(ds);
        validate(ds);
        ds.setId(IdGen.uuid());
        ds.setCreateDate(new java.util.Date());
        ds.setDelFlag("0");
        mapper.insert(ds);
        return ds;
    }

    public DataSource update(String id, DataSource ds) {
        normalizeType(ds);
        validate(ds);
        DataSource old = get(id);
        old.setName(ds.getName());
        // type 允许修改；入参为空时保留原值，避免把列写成 null
        if (ds.getType() != null && !ds.getType().trim().isEmpty()) {
            old.setType(ds.getType().trim().toLowerCase());
        }
        old.setHost(ds.getHost());
        old.setPort(ds.getPort());
        old.setUsername(ds.getUsername());
        old.setPassword(ds.getPassword());
        old.setExtraParams(ds.getExtraParams());
        old.setDefaultDb(ds.getDefaultDb());
        old.setProps(ds.getProps());
        old.setDriverClass(ds.getDriverClass());
        old.setUpdateBy(ds.getUpdateBy());
        old.setUpdateDate(new java.util.Date());
        mapper.update(old);
        return old;
    }

    public void delete(String id) {
        get(id);
        mapper.delete(id, System.currentTimeMillis());
    }

    /** 测试连接（可未保存） */
    public void test(DataSource ds) {
        normalizeType(ds);
        validate(ds);
        metaService.testConnection(ds);
    }

    /** 测试已保存的数据源 */
    public void testSaved(String id) {
        metaService.testConnection(get(id));
    }

    public List<String> databases(String id) {
        return metaService.listDatabases(get(id));
    }

    public List<TableMeta> tables(String id, String db) {
        return metaService.listTables(get(id), db);
    }

    public List<ColumnMeta> columns(String id, String db, String table) {
        return metaService.listColumns(get(id), db, table);
    }

    /** type 为空时补默认值 */
    private void normalizeType(DataSource ds) {
        if (ds.getType() == null || ds.getType().trim().isEmpty()) {
            ds.setType(DEFAULT_TYPE);
            return;
        }
        ds.setType(ds.getType().trim().toLowerCase());
    }

    private void validate(DataSource ds) {
        if (ds.getName() == null || ds.getName().trim().isEmpty()) {
            throw new BizException("数据源名称不能为空");
        }
        if (ds.getHost() == null || ds.getHost().trim().isEmpty()) {
            throw new BizException("主机地址不能为空");
        }
        // 解析 Connector：类型不受支持时立即失败，并把已注册品牌回给用户
        Capabilities caps = registry.get(ds).capabilities();
        if (ds.getPort() == null || ds.getPort() <= 0) {
            // 默认端口改为按类型取（MySQL 仍是 3306，行为不变）
            ds.setPort(caps.defaultPort());
        }
        if (caps.requiresAnchor() && (ds.getDefaultDb() == null || ds.getDefaultDb().trim().isEmpty())) {
            throw new BizException(caps.displayName() + " 的 JDBC URL 必须携带连接库，请填写「连接库（锚点）」");
        }
    }
}
