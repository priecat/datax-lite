package net.itzq.datax;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DataX Lite 启动类
 *
 * 注意：必须在 DataX 任何类加载之前设置 datax.home 系统属性，
 * DataX 的 CoreConstant 会依据该属性定位 {datax.home}/conf/core.json 与 {datax.home}/plugin 目录。
 */
@SpringBootApplication
public class DataxLiteApplication {

    public static void main(String[] args) {
        String home = System.getProperty("datax.home");
        if (home == null || home.trim().isEmpty()) {
            home = System.getProperty("user.dir");
        }
        System.setProperty("datax.home", home);
        // SQLite 数据目录不存在时自动创建（sqlite-jdbc 不会创建父目录）
        java.io.File dataDir = new java.io.File(home, "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            throw new IllegalStateException("无法创建数据目录: " + dataDir.getAbsolutePath());
        }
        ensureCoreJson(home);
        SpringApplication.run(DataxLiteApplication.class, args);
    }

    /**
     * conf/core.json 不存在时从 classpath 模板自动生成（DataX 内核启动必需）。
     * 已存在则不覆盖，允许用户自定义。
     */
    private static void ensureCoreJson(String home) {
        java.io.File confFile = new java.io.File(home, "conf" + java.io.File.separator + "core.json");
        if (confFile.exists()) {
            return;
        }
        try (java.io.InputStream in = DataxLiteApplication.class
                .getResourceAsStream("/datax-default-core.json")) {
            if (in == null) {
                throw new IllegalStateException("classpath 中未找到模板 datax-default-core.json");
            }
            java.io.File confDir = confFile.getParentFile();
            if (!confDir.exists() && !confDir.mkdirs()) {
                throw new IllegalStateException("无法创建配置目录: " + confDir.getAbsolutePath());
            }
            java.nio.file.Files.copy(in, confFile.toPath());
            System.out.println("已生成 DataX 内核配置: " + confFile.getAbsolutePath());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("生成 DataX 内核配置失败: " + confFile.getAbsolutePath(), e);
        }
    }
}
