package net.itzq.datax.connector.pg;

import net.itzq.datax.connector.CanonicalType;
import net.itzq.datax.connector.TypeDecision;
import net.itzq.datax.connector.TypeMapping;
import net.itzq.datax.connector.TypeRuleProvider;
import net.itzq.datax.dto.ColumnMapping;
import net.itzq.datax.dto.ColumnMeta;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * PostgreSQL 类型映射。
 *
 * <p>读侧：{@code format_type} 输出的标准类型名 → {@link CanonicalType}
 * （{@code timestamp(3) with time zone} → TIMESTAMP_TZ、{@code integer[]} → ARRAY 等）。
 *
 * <p>写侧：统一走 {@link TypeMapping#decideWithRules}（显式指定 &gt; 类型字典 &gt; 恒等回退）。
 * PG→PG 恒等行为由「同库方向不 seed 规则」保证，跨库（MySQL→PG 等）由字典规则裁决。
 */
@Component
public class PgTypeMapping implements TypeMapping {

    private static final Map<String, CanonicalType> READ_RULES;

    private final TypeRuleProvider ruleProvider;

    public PgTypeMapping(TypeRuleProvider ruleProvider) {
        this.ruleProvider = ruleProvider;
    }

    static {
        Map<String, CanonicalType> m = new HashMap<String, CanonicalType>();
        // 整型
        m.put("smallint", CanonicalType.INTEGER);
        m.put("int2", CanonicalType.INTEGER);
        m.put("integer", CanonicalType.INTEGER);
        m.put("int", CanonicalType.INTEGER);
        m.put("int4", CanonicalType.INTEGER);
        m.put("bigint", CanonicalType.INTEGER);
        m.put("int8", CanonicalType.INTEGER);
        // 精确小数 / 浮点
        m.put("numeric", CanonicalType.DECIMAL);
        m.put("decimal", CanonicalType.DECIMAL);
        m.put("money", CanonicalType.DECIMAL);
        m.put("real", CanonicalType.FLOAT);
        m.put("float4", CanonicalType.FLOAT);
        m.put("double precision", CanonicalType.FLOAT);
        m.put("float8", CanonicalType.FLOAT);
        // 时间
        m.put("date", CanonicalType.DATE);
        m.put("time", CanonicalType.TIME);
        m.put("time without time zone", CanonicalType.TIME);
        m.put("time with time zone", CanonicalType.TIME);
        m.put("timetz", CanonicalType.TIME);
        m.put("timestamp", CanonicalType.DATETIME);
        m.put("timestamp without time zone", CanonicalType.DATETIME);
        m.put("timestamp with time zone", CanonicalType.TIMESTAMP_TZ);
        m.put("timestamptz", CanonicalType.TIMESTAMP_TZ);
        // 文本
        m.put("character varying", CanonicalType.STRING);
        m.put("varchar", CanonicalType.STRING);
        m.put("character", CanonicalType.STRING);
        m.put("char", CanonicalType.STRING);
        m.put("bpchar", CanonicalType.STRING);
        m.put("text", CanonicalType.TEXT);
        m.put("xml", CanonicalType.TEXT);
        m.put("interval", CanonicalType.TEXT);
        // 二进制
        m.put("bytea", CanonicalType.BLOB);
        // 位串（按二进制归类）
        m.put("bit", CanonicalType.BINARY);
        m.put("bit varying", CanonicalType.BINARY);
        m.put("varbit", CanonicalType.BINARY);
        // 其它
        m.put("json", CanonicalType.JSON);
        m.put("jsonb", CanonicalType.JSON);
        m.put("uuid", CanonicalType.UUID);
        m.put("boolean", CanonicalType.BOOLEAN);
        m.put("bool", CanonicalType.BOOLEAN);
        m.put("serial", CanonicalType.INTEGER);
        m.put("bigserial", CanonicalType.INTEGER);
        m.put("smallserial", CanonicalType.INTEGER);
        READ_RULES = Collections.unmodifiableMap(m);
    }

    @Override
    public CanonicalType readType(String rawType) {
        if (rawType == null || rawType.trim().isEmpty()) {
            return CanonicalType.UNKNOWN;
        }
        // 数组：integer[] / text[] / character varying(64)[] —— 数组语义优先于元素类型
        String t = rawType.trim().toLowerCase();
        if (t.endsWith("[]")) {
            return CanonicalType.ARRAY;
        }
        CanonicalType type = READ_RULES.get(TypeMapping.baseName(rawType));
        return type == null ? CanonicalType.UNKNOWN : type;
    }

    @Override
    public TypeDecision decide(ColumnMapping column, ColumnMeta sourceMeta, String sourceBrand, String targetBrand) {
        return TypeMapping.decideWithRules(this, ruleProvider, column, sourceMeta, sourceBrand, targetBrand);
    }
}
