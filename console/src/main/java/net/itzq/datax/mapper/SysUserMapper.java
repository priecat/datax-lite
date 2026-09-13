package net.itzq.datax.mapper;

import net.itzq.datax.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface SysUserMapper {

    int insert(SysUser u);

    int update(SysUser u);

    int updatePassword(@Param("id") String id, @Param("password") String password,
                       @Param("updateBy") String updateBy, @Param("updateDate") Date updateDate);

    List<SysUser> findAll();

    SysUser findById(@Param("id") String id);

    SysUser findByAccount(@Param("account") String account);

    long count();

    int delete(@Param("id") String id);
}
