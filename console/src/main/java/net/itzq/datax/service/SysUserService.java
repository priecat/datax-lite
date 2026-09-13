package net.itzq.datax.service;

import net.itzq.datax.common.BizException;
import net.itzq.datax.common.IdGen;
import net.itzq.datax.entity.SysUser;
import net.itzq.datax.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

@Service
public class SysUserService {

    private final SysUserMapper userMapper;

    public SysUserService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public List<SysUser> list() {
        List<SysUser> users = userMapper.findAll();
        users.forEach(u -> u.setPassword(null));
        return users;
    }

    public SysUser create(SysUser user) {
        if (!StringUtils.hasText(user.getAccount())) {
            throw new BizException("账号不能为空");
        }
        if (!StringUtils.hasText(user.getName())) {
            throw new BizException("姓名不能为空");
        }
        if (user.getPassword() == null || user.getPassword().length() < 6) {
            throw new BizException("初始密码长度不能少于 6 位");
        }
        if (userMapper.findByAccount(user.getAccount()) != null) {
            throw new BizException("账号已存在: " + user.getAccount());
        }
        user.setId(IdGen.uuid());
        user.setPassword(AuthService.md5(user.getPassword()));
        if (!"0".equals(user.getStatus())) {
            user.setStatus("1");
        }
        user.setCreateDate(new Date());
        user.setUpdateDate(new Date());
        userMapper.insert(user);
        user.setPassword(null);
        return user;
    }

    public SysUser update(String id, SysUser input) {
        SysUser exists = userMapper.findById(id);
        if (exists == null) {
            throw new BizException("用户不存在");
        }
        if (!StringUtils.hasText(input.getName())) {
            throw new BizException("姓名不能为空");
        }
        exists.setName(input.getName());
        exists.setEmail(input.getEmail());
        exists.setStatus(StringUtils.hasText(input.getStatus()) ? input.getStatus() : "1");
        exists.setUpdateBy(input.getUpdateBy());
        exists.setUpdateDate(new Date());
        userMapper.update(exists);
        exists.setPassword(null);
        return exists;
    }

    public void delete(String id, String currentUserId) {
        if (id.equals(currentUserId)) {
            throw new BizException("不能删除当前登录账号");
        }
        if (userMapper.findById(id) == null) {
            throw new BizException("用户不存在");
        }
        userMapper.delete(id);
    }

    public void resetPassword(String id, String newPassword, String operatorAccount) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new BizException("新密码长度不能少于 6 位");
        }
        if (userMapper.findById(id) == null) {
            throw new BizException("用户不存在");
        }
        userMapper.updatePassword(id, AuthService.md5(newPassword), operatorAccount, new Date());
    }
}
