package net.itzq.datax.connector.mysql;

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
 * MySQL 类型映射。
 *
 * <p>读侧：MySQL 类型名 → {@link CanonicalType} 纯查表。
 *
 * <p>写侧：统一走 {@link TypeMapping#decideWithRules}（显式指定 &gt; 类型字典 &gt; 恒等回退）。
 * P0 的 MySQL→MySQL 恒等行为由「同库方向不 seed 规则」保证，裁决流程本身不做任何改写，
 * 因此建表 DDL 与改造前逐字节一致、同库方向不产出 {@code TypeIssue}。
 */
@Component
public class MysqlTypeMapping implements TypeMapping {

    private static final Map<String, CanonicalType> READ_RULES;

    private final TypeRuleProvider ruleProvider;

    public MysqlTypeMapping(TypeRuleProvider ruleProvider) {
        this.ruleProvider = ruleProvider;
    }

    static {
        Map<String, CanonicalType> m = new HashMap<String, CanonicalType>();
        // 整型
        m.put("tinyint", CanonicalType.INTEGER);
        m.put("smallint", CanonicalType.INTEGER);
        m.put("mediumint", CanonicalType.INTEGER);
        m.put("int", CanonicalType.INTEGER);
        m.put("integer", CanonicalType.INTEGER);
        m.put("bigint", CanonicalType.INTEGER);
        m.put("bit", CanonicalType.INTEGER);
        m.put("year", CanonicalType.INTEGER);
        // 精确小数 / 浮点
        m.put("decimal", CanonicalType.DECIMAL);
        m.put("numeric", CanonicalType.DECIMAL);
        m.put("dec", CanonicalType.DECIMAL);
        m.put("float", CanonicalType.FLOAT);
        m.put("double", CanonicalType.FLOAT);
        m.put("real", CanonicalType.FLOAT);
        // 时间
        m.put("date", CanonicalType.DATE);
        m.put("time", CanonicalType.TIME);
        m.put("datetime", CanonicalType.DATETIME);
        m.put("timestamp", CanonicalType.DATETIME);
        // 文本
        m.put("char", CanonicalType.STRING);
        m.put("varchar", CanonicalType.STRING);
        m.put("tinytext", CanonicalType.TEXT);
        m.put("text", CanonicalType.TEXT);
        m.put("mediumtext", CanonicalType.TEXT);
        m.put("longtext", CanonicalType.TEXT);
        // 二进制
        m.put("binary", CanonicalType.BINARY);
        m.put("varbinary", CanonicalType.BINARY);
        m.put("tinyblob", CanonicalType.BLOB);
        m.put("blob", CanonicalType.BLOB);
        m.put("mediumblob", CanonicalType.BLOB);
        m.put("longblob", CanonicalType.BLOB);
        // 其它
        m.put("json", CanonicalType.JSON);
        m.put("enum", CanonicalType.STRING);
        m.put("set", CanonicalType.STRING);
        m.put("geometry", CanonicalType.BINARY);
        READ_RULES = Collections.unmodifiableMap(m);
    }

    @Override
    public CanonicalType readType(String rawType) {
        if (rawType == null || rawType.trim().isEmpty()) {
            return CanonicalType.UNKNOWN;
        }
        CanonicalType type = READ_RULES.get(TypeMapping.baseName(rawType));
        return type == null ? CanonicalType.UNKNOWN : type;
    }

    @Override
    public TypeDecision decide(ColumnMapping column, ColumnMeta sourceMeta, String sourceBrand, String targetBrand) {
        return TypeMapping.decideWithRules(this, ruleProvider, column, sourceMeta, sourceBrand, targetBrand);
    }
}
