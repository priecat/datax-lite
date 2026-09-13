package net.itzq.datax.connector.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.itzq.datax.connector.DataxAdapter;
import net.itzq.datax.dto.ColumnMapping;
import net.itzq.datax.dto.TableMapping;
import net.itzq.datax.dto.TaskOptions;
import net.itzq.datax.entity.DataSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MySQL 的 DataX 插件参数适配（原样搬迁自 {@code DataxJobBuilder.buildContent}）。
 *
 * <p>⚠️ <b>本类直接决定 job.json 的内容与键顺序</b>：Jackson 的 {@code ObjectNode} 底层是
 * LinkedHashMap，序列化顺序 = 插入顺序。因此字段的 put 次序不能调整，否则"逐字节一致"
 * 的回归基线会失败。同理，列名的反引号包装沿用改造前的<b>不转义</b>写法。
 */
@Component
public class MysqlDataxAdapter implements DataxAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_BATCH_SIZE = 1024;

    private final MysqlCapabilities capabilities;
    private final MysqlDialect dialect;

    public MysqlDataxAdapter(MysqlCapabilities capabilities, MysqlDialect dialect) {
        this.capabilities = capabilities;
        this.dialect = dialect;
    }

    @Override
    public String readerPluginName() {
        return "mysqlreader";
    }

    @Override
    public String writerPluginName() {
        return "mysqlwriter";
    }

    @Override
    public ObjectNode buildReaderParameter(DataSource ds, String db, TableMapping tm,
                                           List<ColumnMapping> selected, TaskOptions options) {
        ObjectNode rp = MAPPER.createObjectNode();
        rp.put("username", ds.getUsername());
        rp.put("password", ds.getPassword());

        ArrayNode rcols = rp.putArray("column");
        for (ColumnMapping c : selected) {
            rcols.add("`" + c.getSource() + "`");
        }
        if (tm.getSplitPk() != null && !tm.getSplitPk().trim().isEmpty()) {
            rp.put("splitPk", tm.getSplitPk().trim());
        }
        if (tm.getWhere() != null && !tm.getWhere().trim().isEmpty()) {
            rp.put("where", tm.getWhere().trim());
        }

        ObjectNode rc = rp.putArray("connection").addObject();
        rc.putArray("jdbcUrl").add(capabilities.buildJdbcUrl(ds, db));
        rc.putArray("table").add(tm.getSourceTable());
        return rp;
    }

    @Override
    public ObjectNode buildWriterParameter(DataSource ds, String db, TableMapping tm,
                                           List<ColumnMapping> selected, TaskOptions options) {
        ObjectNode wp = MAPPER.createObjectNode();
        wp.put("username", ds.getUsername());
        wp.put("password", ds.getPassword());
        wp.put("writeMode", options.getWriteMode());
        wp.put("batchSize", options.getBatchSize() <= 0 ? DEFAULT_BATCH_SIZE : options.getBatchSize());

        ArrayNode wcols = wp.putArray("column");
        for (ColumnMapping c : selected) {
            wcols.add("`" + c.getTarget() + "`");
        }
        if (tm.isTruncateBefore()) {
            wp.putArray("preSql").add(dialect.buildTruncateTable(db, tm.getTargetTable()));
        }

        ObjectNode wc = wp.putArray("connection").addObject();
        wc.put("jdbcUrl", capabilities.buildJdbcUrl(ds, db));
        wc.putArray("table").add(tm.getTargetTable());
        return wp;
    }
}
