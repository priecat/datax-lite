package net.itzq.datax.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.itzq.datax.common.BizException;
import net.itzq.datax.entity.DataSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接器注册表：Spring 启动时收集所有 {@link DbConnector}，按 brand 建索引，供编排层按数据源解析。
 *
 * <p>这是"新增一种数据库 = 加一个 Connector，编排层零改动"的落点。
 *
 * <h3>解析规则（对应设计文档 §6.1）</h3>
 * <pre>
 *   effectiveBrand = type + ("-" + props.compatMode)   // 有 compatMode 时
 *   命中顺序：
 *     0. effectiveBrand 精确匹配           例：oceanbase + mysql  → "oceanbase-mysql"
 *     1. type 精确匹配                     例：mysql              → "mysql"
 *     2. compatMode 作为协议族回退           例：oceanbase + mysql 无专属实现 → 回退到 "mysql"
 *     3. 仍未命中 → 抛 BizException
 * </pre>
 * 第 2 条即设计文档里的"回退到 family"——因为 compatMode 的取值（mysql / oracle / pg）
 * 本身就是协议族名，所以无需额外维护一张 family 映射表。
 */
@Slf4j
@Component
public class ConnectorRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_TYPE = "mysql";

    private final Map<String, DbConnector> byBrand = new LinkedHashMap<>();
    private final List<DbConnector> all;

    public ConnectorRegistry(List<DbConnector> connectors) {
        this.all = connectors == null ? Collections.<DbConnector>emptyList() : connectors;
        for (DbConnector c : this.all) {
            String brand = normalize(c.capabilities().brand());
            if (brand == null) {
                log.warn("Connector[{}] 未声明 brand，已忽略", c.getClass().getSimpleName());
                continue;
            }
            if (byBrand.containsKey(brand)) {
                log.warn("Connector brand 重复，后者被忽略: {} （已注册 {}，忽略 {}）",
                        brand, byBrand.get(brand).getClass().getSimpleName(), c.getClass().getSimpleName());
                continue;
            }
            byBrand.put(brand, c);
            log.info("注册 Connector: brand={}, family={}, displayName={}",
                    brand, c.capabilities().family(), c.capabilities().displayName());
        }
        log.info("Connector 注册完成，共 {} 个: {}", byBrand.size(), byBrand.keySet());
    }

    /** 全部已注册的 Connector（供 GET /api/meta/db-types 使用） */
    public List<DbConnector> all() {
        return Collections.unmodifiableList(new ArrayList<>(all));
    }

    /** 按 brand 精确查找，未命中返回 null */
    public DbConnector getByBrand(String brand) {
        String key = normalize(brand);
        return key == null ? null : byBrand.get(key);
    }

    /** 按数据源解析 Connector，未命中抛 BizException */
    public DbConnector get(DataSource ds) {
        String type = normalize(ds == null ? null : ds.getType());
        if (type == null) {
            type = DEFAULT_TYPE;
        }
        String compat = compatModeOf(ds);

        if (compat != null) {
            DbConnector exact = byBrand.get(type + "-" + compat);
            if (exact != null) {
                return exact;
            }
            log.debug("brand[{}] 未注册，继续回退", type + "-" + compat);
        }

        DbConnector typed = byBrand.get(type);
        if (typed != null) {
            return typed;
        }

        if (compat != null) {
            DbConnector family = byBrand.get(compat);
            if (family != null) {
                log.info("数据源类型[{}](compatMode={}) 无专属实现，回退到协议族[{}]", type, compat, compat);
                return family;
            }
        }

        throw new BizException("不支持的数据库类型: " + type
                + (compat == null ? "" : "(compatMode=" + compat + ")")
                + "，已注册品牌: " + byBrand.keySet());
    }

    /**
     * 计算 effectiveBrand：{@code type} 或 {@code type-compatMode}。
     *
     * <p>供前端/日志复用，语义与 {@link #get(DataSource)} 一致。
     */
    public static String effectiveBrand(DataSource ds) {
        String type = normalize(ds == null ? null : ds.getType());
        if (type == null) {
            type = DEFAULT_TYPE;
        }
        String compat = compatModeOf(ds);
        return compat == null ? type : type + "-" + compat;
    }

    /** 读取 {@code props.compatMode}（props 为 JSON 字符串，非法或缺失时返回 null） */
    public static String compatModeOf(DataSource ds) {
        if (ds == null || ds.getProps() == null || ds.getProps().trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(ds.getProps());
            JsonNode compat = node == null ? null : node.get("compatMode");
            return compat == null || compat.isNull() ? null : normalize(compat.asText());
        } catch (Exception e) {
            log.warn("数据源[{}] 的 props 不是合法 JSON，已忽略: {}", ds.getName(), e.getMessage());
            return null;
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase();
        return v.isEmpty() ? null : v;
    }
}
