package net.itzq.datax.engine;

import net.itzq.datax.connector.ConnectorRegistry;
import net.itzq.datax.connector.DbConnector;
import net.itzq.datax.dto.ColumnMapping;
import net.itzq.datax.dto.TableMapping;
import net.itzq.datax.dto.TaskConfig;
import net.itzq.datax.dto.TaskOptions;
import net.itzq.datax.entity.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 由任务映射配置构建标准 DataX job.json。
 *
 * <p>本类已从"写死 mysqlreader / mysqlwriter"改为<b>只做装配</b>：
 * <ul>
 *   <li>插件名与 {@code parameter} 节点由源/目标库各自的 {@code DataxAdapter} 产出；</li>
 *   <li>本类只负责 {@code setting.speed} / {@code setting.errorLimit} 与 {@code content} 循环。</li>
 * </ul>
 *
 * <p>⚠️ <b>键顺序即 JSON 输出顺序</b>（Jackson ObjectNode 底层为 LinkedHashMap）：
 * 原先的顺序是 job → setting → (speed.channel, errorLimit) → content，
 * 每条 content 内是 reader → (name, parameter) → writer → (name, parameter)。
 * 调整 put 次序会破坏"job.json 逐字节一致"的回归基线。
 */
@Component
public class DataxJobBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConnectorRegistry registry;

    public DataxJobBuilder(ConnectorRegistry registry) {
        this.registry = registry;
    }

    public String build(DataSource sourceDs, String sourceDb, DataSource targetDs, String targetDb, TaskConfig config) {
        TaskOptions options = config.getOptions() == null ? new TaskOptions() : config.getOptions();
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode job = root.putObject("job");
        ObjectNode setting = job.putObject("setting");
        setting.putObject("speed").put("channel", options.getChannel() <= 0 ? 1 : options.getChannel());
        ObjectNode errorLimit = setting.putObject("errorLimit");
        if ("percentage".equals(options.getErrorLimitMode())) {
            // 按比例限制：只写 percentage，避免 record 常驻导致比例被 ErrorRecordChecker 忽略
            errorLimit.put("percentage", options.getErrorLimitPercentage());
        } else {
            errorLimit.put("record", options.getErrorLimitRecord());
        }

        DbConnector source = registry.get(sourceDs);
        DbConnector target = registry.get(targetDs);

        ArrayNode content = job.putArray("content");
        for (TableMapping tm : config.getTables()) {
            if (!tm.isEnabled()) {
                continue;
            }
            List<ColumnMapping> selected = filterSelected(tm.getColumns());
            if (selected.isEmpty()) {
                throw new IllegalArgumentException("表[" + tm.getSourceTable() + "]未选择任何同步字段");
            }
            content.add(buildContent(source, target, sourceDs, sourceDb, targetDs, targetDb, tm, selected, options));
        }
        if (content.isEmpty()) {
            throw new IllegalArgumentException("任务中没有启用的同步表");
        }
        return root.toString();
    }

    private ObjectNode buildContent(DbConnector source, DbConnector target,
                                    DataSource sourceDs, String sourceDb,
                                    DataSource targetDs, String targetDb,
                                    TableMapping tm, List<ColumnMapping> selected, TaskOptions options) {
        ObjectNode item = MAPPER.createObjectNode();

        ObjectNode reader = item.putObject("reader");
        reader.put("name", source.datax().readerPluginName());
        reader.set("parameter", source.datax().buildReaderParameter(sourceDs, sourceDb, tm, selected, options));

        ObjectNode writer = item.putObject("writer");
        writer.put("name", target.datax().writerPluginName());
        writer.set("parameter", target.datax().buildWriterParameter(targetDs, targetDb, tm, selected, options));

        return item;
    }

    private List<ColumnMapping> filterSelected(List<ColumnMapping> columns) {
        List<ColumnMapping> result = new ArrayList<ColumnMapping>();
        if (columns != null) {
            for (ColumnMapping c : columns) {
                if (c.isSelected() && c.getSource() != null && c.getTarget() != null) {
                    result.add(c);
                }
            }
        }
        return result;
    }
}
