package net.itzq.datax.connector;

import com.fasterxml.jackson.databind.node.ObjectNode;
import net.itzq.datax.dto.ColumnMapping;
import net.itzq.datax.dto.TableMapping;
import net.itzq.datax.dto.TaskOptions;
import net.itzq.datax.entity.DataSource;

import java.util.List;

/**
 * DataX 插件参数适配：选择 reader/writer 插件名，并构建各自的 {@code parameter}。
 *
 * <p><b>为什么必须独立出这一层</b>：DataX 各 writer 的参数 schema 并不统一
 * （{@code mysqlwriter} 有 {@code writeMode}，{@code postgresqlwriter} 没有；
 * {@code preSql} 语法与引号规则也各异）。把这些差异堆进 JobBuilder 的 if-else，
 * JobBuilder 会迅速腐化。<b>JobBuilder 只做装配，参数一律向本接口索取。</b>
 */
public interface DataxAdapter {

    /** 读取插件名，如 {@code mysqlreader} */
    String readerPluginName();

    /** 写入插件名，如 {@code mysqlwriter} */
    String writerPluginName();

    /**
     * 构建 reader 的 parameter 节点。
     *
     * @param ds       源数据源
     * @param db       源库
     * @param tm       表映射（提供 splitPk / where）
     * @param selected 已勾选列（顺序即 column 参数顺序）
     * @param options  任务级选项
     */
    ObjectNode buildReaderParameter(DataSource ds, String db, TableMapping tm,
                                    List<ColumnMapping> selected, TaskOptions options);

    /**
     * 构建 writer 的 parameter 节点。
     *
     * @param ds       目标数据源
     * @param db       目标库（TRUNCATE preSql 需要）
     * @param tm       表映射（提供 targetTable / truncateBefore）
     * @param selected 已勾选列
     * @param options  任务级选项（writeMode / batchSize）
     */
    ObjectNode buildWriterParameter(DataSource ds, String db, TableMapping tm,
                                    List<ColumnMapping> selected, TaskOptions options);
}
