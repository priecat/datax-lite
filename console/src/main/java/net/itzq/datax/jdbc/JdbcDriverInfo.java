package net.itzq.datax.jdbc;

import lombok.Data;

/**
 * jdbc/ 目录中发现的一个驱动 jar 的描述。
 *
 * <p>解析标准 SPI 声明（{@code META-INF/services/java.sql.Driver}），一个 jar 可声明多个驱动类
 * （如同时含 mysql 与 mariadb 驱动的 jar）。
 */
@Data
public class JdbcDriverInfo {

    /** jar 文件名，如 mysql-connector-java-8.0.33.jar */
    private String fileName;

    /** jar 内声明的全部驱动实现类 */
    private java.util.List<String> driverClasses = new java.util.ArrayList<String>();

    /** 第一个驱动实例的版本（major.minor），无法实例化时为 null */
    private String version;

    /** 哪些品牌档案的 driverClass 与本 jar 内驱动匹配，如 ["mysql"] */
    private java.util.List<String> matchedBrands = new java.util.ArrayList<String>();

    /** 驱动实例化是否成功（失败时 jars 缺依赖或类损坏，列表页可见但不提供连接） */
    private boolean usable;
}
