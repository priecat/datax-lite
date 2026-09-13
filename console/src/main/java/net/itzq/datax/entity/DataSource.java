package net.itzq.datax.entity;

import lombok.Data;

import java.util.Date;

@Data
public class DataSource {

    private String id;
    private String name;
    private String type;
    private String host;
    private Integer port;
    private String username;
    private String password;
    /** 追加到 jdbc url 的额外参数，如 useSSL=false&characterEncoding=utf8 */
    private String extraParams;
    /**
     * JDBC 驱动实现类全名。驱动 jar 由部署目录 jdbc/ 统一提供；
     * 为空时使用品牌档案的默认驱动（capabilities.driverClass），用户可换用同库的其它版本驱动。
     */
    private String driverClass;
    /**
     * 连接锚点库：建连时必须存在并可连接的库/模式。
     * PG、达梦为必填（其 JDBC URL 必须携带库名）；MySQL 族忽略。
     */
    private String defaultDb;
    /**
     * 类型特有字段的 JSON 载体（如 compatMode、serviceName）。
     * 仅存用户意图数据；family / namespaceKind 等可派生的信息一律运行时从品牌档案计算，不落库。
     */
    private String props;
    private String createBy;
    private Date createDate;
    private String updateBy;
    private Date updateDate;
    private String delFlag;
}
