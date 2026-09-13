package net.itzq.datax.entity;

import lombok.Data;

import java.util.Date;

/** 字典类型：code 约定 db_{brand}_field（如 db_mysql_field），供前端下拉渲染 */
@Data
public class DictType {

    private String id;
    private String code;
    private String name;
    private String remark;
    /** 系统预设（1）不可删除，但预设值仍可由用户补充 */
    private Integer isSystem;
    private Date createDate;
    private Date updateDate;
    private String delFlag;
}
