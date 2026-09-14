package net.itzq.datax.connector;

import net.itzq.datax.dto.ColumnMeta;
import net.itzq.datax.dto.TableMeta;
import net.itzq.datax.entity.DataSource;

import java.sql.Connection;
import java.util.List;

/**
 * 元数据读取 + JDBC 直连操作。
 *
 * <p>职责边界：<b>负责开连接、绑参数、执行 {@link Dialect} 给出的 SQL、把结果集映射为中立 DTO</b>。
 * 编排层只依赖本接口，不感知任何具体数据库。
 *
 * <p>所有实现都必须把 SQL 异常包装成 {@code BizException}，并保持与改造前一致的错误文案
 * （这些文案会出现在任务日志与预览告警里）。
 */
public interface Metadata {

    /** 打开连接。{@code db} 为 null 表示不指定库（MySQL 允许；PG/达梦需靠 {@code defaultDb} 锚点） */
    Connection open(DataSource ds, String db);

    /** 测试连接，失败抛 BizException */
    void testConnection(DataSource ds);

    /** 服务端版本（日志用） */
    String serverVersion(DataSource ds);

    List<String> listDatabases(DataSource ds);

    List<TableMeta> listTables(DataSource ds, String db);

    List<ColumnMeta> listColumns(DataSource ds, String db, String table);

    boolean tableExists(DataSource ds, String db, String table);

    /** 源表建表语句原文；找不到抛 BizException */
    String showCreateTable(DataSource ds, String db, String table);

    /**
     * 列出表上的触发器。默认空列表（不支持触发器的方言），
     * MySQL 实现开连接后委派 {@code Dialect#listTriggers}。
     */
    default List<Dialect.TriggerDef> listTriggers(DataSource ds, String db, String table) {
        return java.util.Collections.emptyList();
    }

    /**
     * 读表的自增计数器当前值（下一个将分配的自增 id）。
     * 默认 null（方言无自增语义或读取不适用）；表无自增列时 MySQL 实现也返回 null。
     */
    default Long readAutoIncrement(DataSource ds, String db, String table) {
        return null;
    }

    /** 在目标库执行一条 DDL */
    void executeDdl(DataSource ds, String db, String ddl);
}
