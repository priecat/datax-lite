package net.itzq.datax.connector;

import net.itzq.datax.entity.DataSource;

import java.util.List;

/**
 * 能力声明：一种数据库在系统里的"静态画像"。
 *
 * <p>变化频率最低（几乎不变），因此适合声明式描述 —— P1 起可由
 * {@code {datax.home}/conf/db-profiles/{brand}.json} 承载，新增库不改代码。
 *
 * <p>其中 {@link #nativeShowCreate()} 与 {@link #requiresAnchor()} 是两个关键能力位：
 * 前者决定建表能否走"源库建表语句原样拷贝"快路径；后者决定 JDBC URL 是否必须带连接锚点库。
 */
public interface Capabilities {

    /** 品牌标识，注册表的精确命中键，如 {@code mysql} / {@code postgresql} / {@code oceanbase-mysql} */
    String brand();

    /** 协议族（方言族），brand 未命中时的回退键：{@code mysql} / {@code pg} / {@code oracle} */
    String family();

    /** 展示名 */
    String displayName();

    /** 默认端口 */
    int defaultPort();

    /** JDBC URL 模板，占位符：{host} {port} {database} {default_db} */
    String jdbcUrlTemplate();

    /** JDBC 驱动类名（P0 仅声明，实际加载由 DriverManager + 插件 libs 决定） */
    String driverClass();

    /** 命名空间语义：任务的 database 字段指向 DATABASE 还是 SCHEMA */
    NamespaceKind namespaceKind();

    /**
     * 建连是否必须携带一个已存在的库/锚点。
     * PG、达梦为 true（首次"测试连接"就必须给库名）；MySQL 族为 false。
     */
    boolean requiresAnchor();

    /**
     * 是否支持"源表 DDL 原样拷贝"快路径（MySQL 的 {@code SHOW CREATE TABLE}）。
     * 为 true 时，身份映射表可直接复用源库建表语句，保留索引等完整定义。
     */
    boolean nativeShowCreate();

    boolean supportsSchema();

    boolean supportsSplitPk();

    boolean supportsUpsert();

    boolean supportsDdl();

    /** 是否支持列注释（达梦的 ALTER ADD COLUMN 不支持内联 COMMENT） */
    boolean supportsComment();

    boolean supportsCharset();

    /** 支持的写入模式，如 ["insert","replace","update"]；前端据此渲染选项 */
    List<String> writeModes();

    /** 前端动态表单字段 schema */
    List<FieldSpec> fields();

    /** 「额外参数」输入框的占位提示 */
    String extraParamsPlaceholder();

    /**
     * 构建 JDBC URL。
     *
     * <p>P0 的 MySQL 实现逐字符复刻原 {@code MetaService.buildJdbcUrl} 的输出，
     * 保证存量任务行为不变。
     */
    String buildJdbcUrl(DataSource ds, String db);
}
