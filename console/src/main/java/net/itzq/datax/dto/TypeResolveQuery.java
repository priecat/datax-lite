package net.itzq.datax.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 「系统默认」类型裁决的批量查询（字段映射对话框「目标类型(最终)」列展示用）。
 *
 * <p>只走「类型字典 -> 恒等回退」分支（不含用户显式 targetType）：
 * 前端在用户未指定类型时展示该结果，用户指定后自行叠加。
 */
@Data
public class TypeResolveQuery {

    private String sourceDatasourceId;
    private String targetDatasourceId;
    /** 源类型原文列表（如 bigint(20)、character varying） */
    private List<String> sourceTypes = new ArrayList<String>();

    @Data
    public static class Item {
        /** 入参源类型原文 */
        private String sourceType;
        /** 系统默认裁决出的目标类型 */
        private String targetType;
        /** 与源类型原文一致（恒等回退；跨库时建议用户补字典规则或显式指定） */
        private boolean sameAsSource;
        /** 裁决产生的提示文本（如跨库未配置映射规则的 WARN），无则为 null */
        private List<String> issues;
    }
}
