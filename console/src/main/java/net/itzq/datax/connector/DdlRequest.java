package net.itzq.datax.connector;

import lombok.Data;

import java.util.List;

/**
 * 建表 DDL 的入参（中立，不含任何具体数据库的语义）。
 *
 * <p>由编排层（DdlGenerator）组装，交给目标库的 {@link Dialect#buildCreateTable} 渲染。
 */
@Data
public class DdlRequest {

    /** 目标库（MySQL 语义下即 schema） */
    private String targetDb;

    /** 目标表名 */
    private String targetTable;

    /** 已解析的列（顺序即 DDL 中的列顺序） */
    private List<ResolvedColumn> selected;

    /**
     * 是否走"身份拷贝"快路径：目标表名 == 源表名 且 全字段选中且未改名。
     * 该路径下方言若支持 {@link Capabilities#nativeShowCreate()}，可直接使用源库建表语句。
     */
    private boolean identity;

    /** identity == true 时，源库建表语句的<b>表体</b>部分（已由 Dialect 抽取），其余情况为 null */
    private String sourceCreateTable;
}
