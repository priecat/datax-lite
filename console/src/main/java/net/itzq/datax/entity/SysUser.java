package net.itzq.datax.entity;

import lombok.Data;

import java.util.Date;

@Data
public class SysUser {

    private String id;
    private String account;
    private String name;
    private String password;
    private String email;
    /** 1-启用 0-禁用 */
    private String status;
    private String createBy;
    private Date createDate;
    private String updateBy;
    private Date updateDate;
    private String delFlag;
}
