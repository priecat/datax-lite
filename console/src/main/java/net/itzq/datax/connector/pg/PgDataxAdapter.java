package net.itzq.datax.connector.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.itzq.datax.common.BizException;
import net.itzq.datax.connector.DataxAdapter;
import net.itzq.datax.dto.ColumnMapping;
import net.itzq.datax.dto.TableMapping;
import net.itzq.datax.dto.TaskOptions;
import net.itzq.datax.entity.DataSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PostgreSQL 的 DataX 插件参数适配（postgresqlreader / postgresqlwriter）。
 *
 * <p>与 MySQL 适配的关键差异：
 * <ul>
 *   <li>列名用<b>双引号</b>包装（PG 标识符规则），而非反引号；</li>
 *   <li>官方 postgresqlwriter <b>没有 writeMode 参数</b>（仅 insert 语义），
 *       任务若配置了非 insert 写入模式在此显式拒绝；</li>
 *   <li>reader 侧 jdbcUrl 为数组、writer 侧为字符串 —— 与官方插件约定一致。</li>
 * </ul>
 */
@Component
public class PgDataxAdapter implements DataxAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_BATCH_SIZE = 1024;

    private final PgCapabilities capabilities;
    private final PgDialect dialect;

    public PgDataxAdapter(PgCapabilities capabilities, PgDialect dialect) {
        this.capabilities = capabilities;
        this.dialect = dialect;
    }

    @Override
    public String readerPluginName() {
        return "postgresqlreader";
    }

    @Override
    public String writerPluginName() {
        return "postgresqlwriter";
    }

    @Override
    public ObjectNode buildReaderParameter(DataSource ds, String db, TableMapping tm,
                                           List<ColumnMapping> selected, TaskOptions options) {
        ObjectNode rp = MAPPER.createObjectNode();
        rp.put("username", ds.getUsername());
        rp.put("password", ds.getPassword());

        ArrayNode rcols = rp.putArray("column");
        for (ColumnMapping c : selected) {
            rcols.add(dialect.quote(c.getSource()));
        }
        if (tm.getSplitPk() != null && !tm.getSplitPk().trim().isEmpty()) {
            rp.put("splitPk", tm.getSplitPk().trim());
        }
        if (tm.getWhere() != null && !tm.getWhere().trim().isEmpty()) {
            rp.put("where", tm.getWhere().trim());
        }

        ObjectNode rc = rp.putArray("connection").addObject();
        rc.putArray("jdbcUrl").add(capabilities.buildJdbcUrl(ds, db));
        // rdbms 系插件的 table 参数必须带 schema 前缀（PG 无反引号方言，search_path 不指向业务 schema）
        rc.putArray("table").add(db + "." + tm.getSourceTable());
        return rp;
    }

    @Override
    public ObjectNode buildWriterParameter(DataSource ds, String db, TableMapping tm,
                                           List<ColumnMapping> selected, TaskOptions options) {
        String mode = options == null ? "insert" : options.getWriteMode();
        if (mode != null && !"insert".equalsIgnoreCase(mode)) {
            throw new BizException("PostgreSQL 仅支持 insert 写入模式，当前配置: " + mode);
        }

        ObjectNode wp = MAPPER.createObjectNode();
        wp.put("username", ds.getUsername());
        wp.put("password", ds.getPassword());
        wp.put("batchSize", options.getBatchSize() <= 0 ? DEFAULT_BATCH_SIZE : options.getBatchSize());

        ArrayNode wcols = wp.putArray("column");
        for (ColumnMapping c : selected) {
            wcols.add(dialect.quote(c.getTarget()));
        }
        if (tm.isTruncateBefore()) {
            wp.putArray("preSql").add(dialect.buildTruncateTable(db, tm.getTargetTable()));
        }

        ObjectNode wc = wp.putArray("connection").addObject();
        wc.put("jdbcUrl", capabilities.buildJdbcUrl(ds, db));
        wc.putArray("table").add(db + "." + tm.getTargetTable());
        return wp;
    }
}
