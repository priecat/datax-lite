package net.itzq.datax.entity;

import lombok.Data;

import java.util.Date;

/**
 * 字典 KV：目标库字段类型下拉的选项。
 *
 * <p>label 为前端显示名（如「整型 int」「长文本」），value 为落进 DDL 的目标类型
 * （如 {@code int}、{@code longtext}）。用户自定义条目（如 长文本=>longtext）即在此增配。
 */
@Data
public class DictValue {

    private String id;
    private String dictCode;
    private String label;
    private String value;
    private Integer sort;
    private String remark;
    private Integer isSystem;
    private Date createDate;
    private Date updateDate;
    private String delFlag;
}
