package net.itzq.datax.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.annotation.PreDestroy;

/**
 * 外置 JDBC 驱动管理：扫描 {@code {user.dir}/jdbc/*.jar}，按标准 SPI 声明加载驱动实例。
 *
 * <p>设计要点：
 * <ul>
 *   <li>项目 fat jar <b>不自带任何数据库驱动</b>，驱动由部署目录 {@code jdbc/} 统一提供
 *       （可从官方 DataX 插件 libs 或 Maven 仓库拷入，版本由部署者掌控）；</li>
 *   <li>每个 jar 一个独立 {@link URLClassLoader}（父加载器 = 应用类加载器），
 *       不同版本驱动互不干扰；</li>
 *   <li>建连不经过 {@code DriverManager}（其按调用方加载器搜索驱动的规则会漏掉外置加载的驱动），
 *       而是直接持有 {@link Driver} 实例调用 {@link Driver#connect(String, Properties)}。</li>
 * </ul>
 */
@Slf4j
@Component
public class JdbcDriverHolder {

    /** 与 plugin/、data/ 同级的驱动目录 */
    private static final File DRIVER_DIR = new File(System.getProperty("user.dir"), "jdbc");

    /** driverClass -> 已实例化的 Driver（跨 jar 去重，首个实例化成功者优先） */
    private final Map<String, Driver> drivers = new LinkedHashMap<String, Driver>();

    /** jar 文件名 -> 扫描结果 */
    private final Map<String, JdbcDriverInfo> infos = new LinkedHashMap<String, JdbcDriverInfo>();

    /** 已打开的类加载器（refresh 时关闭旧实例） */
    private final List<URLClassLoader> loaders = new ArrayList<URLClassLoader>();

    public JdbcDriverHolder() {
        scan();
    }

    /** 扫描/重扫驱动目录。目录不存在视为空；单个 jar 失败不影响其它 jar。 */
    public synchronized List<JdbcDriverInfo> scan() {
        closeLoaders();
        drivers.clear();
        infos.clear();

        File[] jars = DRIVER_DIR.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            log.info("jdbc 驱动目录为空: {}", DRIVER_DIR.getAbsolutePath());
            return new ArrayList<JdbcDriverInfo>();
        }

        for (File jar : jars) {
            try {
                URLClassLoader loader = new URLClassLoader(
                        new URL[]{jar.toURI().toURL()}, JdbcDriverHolder.class.getClassLoader());
                loaders.add(loader);
                loadJar(jar, loader);
            } catch (Exception e) {
                log.warn("加载驱动 jar 失败: {} ({})", jar.getName(), e.getMessage());
            }
        }
        log.info("jdbc 驱动扫描完成: {} 个 jar, {} 个驱动类", infos.size(), drivers.size());
        return new ArrayList<JdbcDriverInfo>(infos.values());
    }

    private void loadJar(File jar, URLClassLoader loader) throws IOException {
        JdbcDriverInfo info = new JdbcDriverInfo();
        info.setFileName(jar.getName());

        // 直接从 jar 包内读取 SPI 声明；不能用 loader.getResourceAsStream ——
        // 其父优先委托会命中应用 classpath 上（如 sqlite-jdbc）的同名 SPI 文件
        List<String> classNames = new ArrayList<String>();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            java.util.jar.JarEntry entry = jf.getJarEntry("META-INF/services/java.sql.Driver");
            if (entry != null) {
                for (String line : readLines(jf.getInputStream(entry))) {
                    if (!classNames.contains(line)) {
                        classNames.add(line);
                    }
                }
            }
        }
        for (String cls : classNames) {
            info.getDriverClasses().add(cls);
            if (drivers.containsKey(cls)) {
                continue;
            }
            try {
                Driver driver = (Driver) Class.forName(cls, true, loader).getDeclaredConstructor().newInstance();
                drivers.put(cls, driver);
                info.setVersion(driver.getMajorVersion() + "." + driver.getMinorVersion());
                info.setUsable(true);
            } catch (Throwable t) {
                log.warn("驱动实例化失败: {} in {} ({})", cls, jar.getName(), t.getMessage());
            }
        }
        infos.put(jar.getName(), info);
    }

    private List<String> readLines(InputStream in) throws IOException {
        List<String> lines = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    /** 全部扫描结果（供列表 API） */
    public synchronized List<JdbcDriverInfo> list() {
        return new ArrayList<JdbcDriverInfo>(infos.values());
    }

    /** 是否已有任一驱动 jar */
    public synchronized boolean isEmpty() {
        return infos.isEmpty();
    }

    /**
     * 按驱动类名取 Driver 实例；未加载返回 null（调用方给出明确的部署提示）。
     */
    public synchronized Driver find(String driverClass) {
        return driverClass == null ? null : drivers.get(driverClass.trim());
    }

    @PreDestroy
    public synchronized void closeLoaders() {
        for (URLClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // 关闭失败不影响退出
            }
        }
        loaders.clear();
    }
}
