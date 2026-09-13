package net.itzq.datax.connector;

/**
 * 类型映射规则的查询接缝：connector 层不感知字典存储，
 * 由 service 层提供「源品牌 -> 目标品牌」方向的规则查询。
 */
public interface TypeRuleProvider {

    /**
     * 查映射规则。
     *
     * @param sourceBrand     源品牌（如 postgresql）
     * @param targetBrand     目标品牌（如 mysql）
     * @param sourceTypeBase  源类型基名（小写、去括号，如 varchar）
     * @return 目标 DDL 类型；无规则返回 null（调用方走恒等回退）
     */
    String resolve(String sourceBrand, String targetBrand, String sourceTypeBase);
}
