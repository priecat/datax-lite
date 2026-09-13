package net.itzq.datax.service;

import net.itzq.datax.dto.PluginInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * DataX 插件管理：扫描 {datax.home}/plugin/reader|writer 目录。
 *
 * <p>新插件放进去后<b>无需重启</b>即可生效（{@code ConfigParser} 在每次作业执行时都会重扫插件目录）；
 * 但<b>替换同名插件必须重启</b>（{@code LoadUtil.jarLoaderCenter} 是静态缓存且 URLClassLoader 不可重载）。
 *
 * <p>启动时会做一次<b>重名检测</b>：{@code ConfigParser.parseOnePluginConfig} 在过滤目标插件
 * <b>之前</b>就对全部插件目录做唯一性校验，命中重名即抛 {@code PLUGIN_INIT_ERROR}，
 * 因此一个重名目录会让<b>所有</b>作业硬失败。这里提前发现并给出明确指引。
 */
@Slf4j
@Service
public class PluginService implements ApplicationRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 这些子目录/文件属于描述符与扩展，不算插件资产 */
    private static final String EXTENSION_DIR = "extension";
    private static final String CONNECTOR_DESC = "connector.json";

    /** 命中重名的插件名（启动时填充，供 /api/plugins 回显） */
    private volatile Set<String> duplicatedNames = new HashSet<String>();

    public List<PluginInfo> listPlugins() {
        List<PluginInfo> result = new ArrayList<PluginInfo>();
        String home = System.getProperty("datax.home", System.getProperty("user.dir"));
        Path pluginRoot = Paths.get(home, "plugin");
        for (String type : Arrays.asList("reader", "writer")) {
            Path typeDir = pluginRoot.resolve(type);
            if (!Files.isDirectory(typeDir)) {
                continue;
            }
            File[] dirs = typeDir.toFile().listFiles(File::isDirectory);
            if (dirs == null) {
                continue;
            }
            for (File dir : dirs) {
                try {
                    result.add(readPlugin(dir, type));
                } catch (Exception e) {
                    log.warn("读取插件[{}]失败: {}", dir.getName(), e.getMessage());
                }
            }
        }
        return result;
    }

    private PluginInfo readPlugin(File dir, String type) throws Exception {
        PluginInfo info = new PluginInfo();
        info.setType(type);
        info.setPath(dir.getAbsolutePath());
        info.setModified(Files.getLastModifiedTime(dir.toPath()).toMillis());

        File pluginJson = new File(dir, "plugin.json");
        if (pluginJson.exists()) {
            JsonNode node = MAPPER.readTree(pluginJson);
            info.setName(node.path("name").asText(dir.getName()));
            info.setDescription(node.path("description").asText(null));
            info.setDeveloper(node.path("developer").asText(null));
        } else {
            info.setName(dir.getName());
            log.warn("插件目录缺少 plugin.json: {}", dir.getAbsolutePath());
        }
        info.setDuplicatedName(duplicatedNames.contains(info.getName()));

        List<String> jars = new ArrayList<String>();
        File topJar = topJar(dir);
        if (topJar != null) {
            info.setVersion(extractVersion(topJar.getName(), info.getName()));
        }
        try (Stream<Path> stream = Files.walk(dir.toPath(), 2)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !isAuxiliary(p))
                    .forEach(p -> jars.add(dir.toPath().relativize(p).toString()));
        }
        info.setJars(jars);
        return info;
    }

    /**
     * 是否属于附属资产（不属于插件本体，不应计入 jar 清单）。
     *
     * <p>{@code extension/} 是编程式兜底实现，{@code connector.json} 是描述符 ——
     * 前者可能含 jar 但那是 console 侧扩展，后者根本不含 jar。把它们排除是为了让插件列表
     * 只呈现插件本体，避免误导。
     */
    private boolean isAuxiliary(Path path) {
        String s = path.toString().replace('\\', '/');
        return s.contains("/" + EXTENSION_DIR + "/") || s.endsWith(CONNECTOR_DESC);
    }

    private File topJar(File dir) {
        File[] files = dir.listFiles(f -> f.getName().endsWith(".jar"));
        return (files == null || files.length == 0) ? null : files[0];
    }

    private String extractVersion(String jarName, String pluginName) {
        String base = jarName.substring(0, jarName.length() - 4);
        if (base.startsWith(pluginName + "-")) {
            return base.substring(pluginName.length() + 1);
        }
        return base;
    }

    /**
     * 启动期插件重名检测。
     *
     * <p>注意：<b>只告警不阻断启动</b>。重名会让作业在运行期硬失败（这是既成事实，不是本次改动引入的），
     * 但让应用因一个多余目录而起不来，会连累数据源/用户等无关功能，代价更大。
     * 若希望改为 fail-fast，把下面的 log.error 换成抛异常即可。
     */
    @Override
    public void run(ApplicationArguments args) {
        String home = System.getProperty("datax.home", System.getProperty("user.dir"));
        Path pluginRoot = Paths.get(home, "plugin");
        Map<String, List<String>> byName = new HashMap<String, List<String>>();
        for (String type : Arrays.asList("reader", "writer")) {
            Path typeDir = pluginRoot.resolve(type);
            if (!Files.isDirectory(typeDir)) {
                continue;
            }
            File[] dirs = typeDir.toFile().listFiles(File::isDirectory);
            if (dirs == null) {
                continue;
            }
            for (File dir : dirs) {
                String name = pluginNameOf(dir, type);
                List<String> paths = byName.get(name);
                if (paths == null) {
                    paths = new ArrayList<String>();
                    byName.put(name, paths);
                }
                paths.add(type + "/" + dir.getName());
            }
        }
        Set<String> dups = new HashSet<String>();
        for (Map.Entry<String, List<String>> e : byName.entrySet()) {
            if (e.getValue().size() > 1) {
                dups.add(e.getKey());
                log.error("检测到重复插件名[{}]，来源: {}。"
                                + "DataX 在加载插件时会对全部插件目录做唯一性校验，"
                                + "重复名会让【所有】作业直接失败，请删除多余目录后重启。",
                        e.getKey(), e.getValue());
            }
        }
        this.duplicatedNames = dups;
        if (dups.isEmpty()) {
            log.info("插件重名检测通过，共 {} 个插件目录", byName.size());
        }
    }

    private String pluginNameOf(File dir, String type) {
        File pluginJson = new File(dir, "plugin.json");
        if (pluginJson.exists()) {
            try {
                JsonNode node = MAPPER.readTree(pluginJson);
                String name = node.path("name").asText(null);
                if (name != null && !name.trim().isEmpty()) {
                    return name;
                }
            } catch (Exception ignored) {
                // 解析失败时退回目录名
            }
        }
        return dir.getName();
    }
}
