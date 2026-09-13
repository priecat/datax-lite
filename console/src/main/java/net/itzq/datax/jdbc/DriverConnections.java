package net.itzq.datax.jdbc;

import net.itzq.datax.common.BizException;

import java.sql.Connection;
import java.sql.Driver;
import java.util.Properties;

/**
 * 外置驱动的统一建连入口。
 *
 * <p>不经过 {@code DriverManager}（它会按调用方类加载器搜索已注册驱动，外置 URLClassLoader
 * 加载的驱动会被漏掉），而是直接调用 {@link Driver#connect(String, Properties)}。
 */
public final class DriverConnections {

    private DriverConnections() {
    }

    /**
     * 用指定驱动类建连。
     *
     * @param driverClass 期望的驱动类（来自数据源的 driverClass 或品牌档案默认值）
     * @return 连接；驱动缺失/无法处理该 URL 时抛 {@link BizException}，文案给出部署指引
     */
    public static Connection open(JdbcDriverHolder holder, String driverClass,
                                  String url, String username, String password) {
        Driver driver = holder.find(driverClass);
        if (driver == null) {
            throw new BizException("JDBC 驱动未找到: " + driverClass
                    + "。请将对应驱动 jar 放入部署目录的 jdbc/ 文件夹后重启（或在前端驱动列表刷新）");
        }
        Properties props = new Properties();
        if (username != null) {
            props.setProperty("user", username);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        try {
            Connection conn = driver.connect(url, props);
            if (conn == null) {
                throw new BizException("驱动[" + driverClass + "]无法处理该 URL: " + url
                        + "。可在数据源表单中更换其它已加载的驱动再试");
            }
            return conn;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("连接失败: " + e.getMessage(), e);
        }
    }
}
