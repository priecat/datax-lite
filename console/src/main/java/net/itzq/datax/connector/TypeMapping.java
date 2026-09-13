package net.itzq.datax.connector;

import net.itzq.datax.dto.ColumnMapping;
import net.itzq.datax.dto.ColumnMeta;

/**
 * 类型映射：读侧（源类型 → {@link CanonicalType}）+ 写侧（→ 目标类型）。
 *
 * <p>这是变化频率最高的组件（类型表改得最勤），因此单独成接口，不与方言混在一起。
 *
 * <p>写侧裁决 {@link #decide} 是<b>唯一出口</b>，统一优先级：
 * <ol>
 *   <li>列映射上用户显式指定的 {@code targetType}（含 {@code targetTypeLen} 长度合成）；</li>
 *   <li>类型映射字典规则（{@code sys_type_map}，按 源品牌-&gt;目标品牌 分组 + 源类型基名）；</li>
 *   <li>恒等回退 —— 源类型原文直传；跨库（brand 不同）且无规则命中时追加 WARN issue。</li>
 * </ol>
 * 各实现只需提供 {@link #readType} 与 {@link TypeRuleProvider}，裁决流程统一走
 * {@link #decideWithRules}，避免多实现复制粘贴漂移。
 */
public interface TypeMapping {

    /** 读侧：把数据库原始类型字符串归类到设计期规范类型 */
    CanonicalType readType(String rawType);

    /**
     * 写侧：决定目标类型（唯一裁决出口）。
     *
     * @param column      列映射（含 source / target / sourceType / targetType / targetTypeLen）
     * @param sourceMeta  源列元数据（可为 null，如补列路径）
     * @param sourceBrand 源品牌（如 postgresql），未知可传 null
     * @param targetBrand 目标品牌（如 mysql），未知可传 null
     */
    TypeDecision decide(ColumnMapping column, ColumnMeta sourceMeta, String sourceBrand, String targetBrand);

    /**
     * 统一裁决流程：显式指定 &gt; 字典规则 &gt; 恒等回退（跨库未命中追加 WARN）。
     *
     * @param self  当前 TypeMapping（用其 readType 解析裁决结果的规范类型）
     * @param rules 字典规则提供者（DictService）
     */
    static TypeDecision decideWithRules(TypeMapping self, TypeRuleProvider rules,
                                        ColumnMapping column, ColumnMeta sourceMeta,
                                        String sourceBrand, String targetBrand) {
        // 1. 任务映射里用户显式指定的目标类型优先（含长度合成 numeric + 10,2 -> numeric(10,2)）
        String explicit = explicitTargetType(column);
        if (explicit != null) {
            return new TypeDecision(explicit, self.readType(explicit));
        }
        String raw = sourceMeta != null && sourceMeta.getType() != null ? sourceMeta.getType()
                : (column != null ? column.getSourceType() : null);
        // 2. 类型映射字典（源品牌->目标品牌 分组，同库方向的自定义规则同样生效）
        if (raw != null && sourceBrand != null && targetBrand != null) {
            String rule = rules.resolve(sourceBrand, targetBrand, baseName(raw));
            if (rule != null) {
                return new TypeDecision(rule, self.readType(rule));
            }
        }
        // 3. 恒等回退；跨库且无规则命中时给出 WARN（同库恒等不产 issue，保持 P0 行为）
        TypeDecision d = new TypeDecision(raw, self.readType(raw));
        if (raw != null && sourceBrand != null && targetBrand != null && !sourceBrand.equals(targetBrand)) {
            d.getIssues().add(TypeIssue.of(IssueLevel.WARN, column == null ? null : column.getSource(),
                    raw, raw, "未配置 " + sourceBrand + "->" + targetBrand + " 的映射规则，按源类型原文直传，"
                            + "请确认目标库支持该类型或在「类型字典」中补充规则"));
        }
        return d;
    }

    /**
     * 显式指定的最终目标类型：{@code targetType + targetTypeLen} 合成
     * （如 numeric + 10,2 → numeric(10,2)；targetType 自带括号时忽略长度）。
     * 未显式指定返回 null，调用方继续走字典/恒等裁决。
     */
    static String explicitTargetType(ColumnMapping column) {
        if (column == null) {
            return null;
        }
        String tt = column.getTargetType();
        if (tt == null || tt.trim().isEmpty()) {
            return null;
        }
        tt = tt.trim();
        String len = column.getTargetTypeLen();
        if (len != null && !len.trim().isEmpty() && !tt.contains("(") && !tt.contains(")")) {
            String l = len.trim().replaceAll("\\s", "");
            if (!l.isEmpty()) {
                tt = tt + "(" + l + ")";
            }
        }
        return tt;
    }

    /**
     * 统一基名剥离：小写、去括号及括号后内容（精度/显示宽度）、去 unsigned/zerofill 修饰。
     * 例：{@code bigint(20) unsigned} → bigint；{@code varchar(64)} → varchar；
     * {@code timestamp(3) with time zone} → timestamp with time zone。
     * unsigned/zerofill 对非 MySQL 类型名无副作用（其他库不会出现这两个词）。
     */
    static String baseName(String rawType) {
        String t = rawType.trim().toLowerCase();
        int paren = t.indexOf('(');
        if (paren > 0) {
            t = t.substring(0, paren);
        }
        String[] parts = t.split("\\s+");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!"unsigned".equals(parts[i]) && !"zerofill".equals(parts[i])) {
                sb.append(' ').append(parts[i]);
            }
        }
        return sb.toString().trim();
    }
}
