package net.itzq.datax.service;

import net.itzq.datax.common.BizException;
import net.itzq.datax.common.IdGen;
import net.itzq.datax.common.JwtUtil;
import net.itzq.datax.config.AppProperties;
import net.itzq.datax.entity.SysUser;
import net.itzq.datax.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final AppProperties properties;

    public AuthService(SysUserMapper userMapper, JwtUtil jwtUtil, AppProperties properties) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.properties = properties;
    }

    /** 首次启动且用户表为空时，按配置初始化管理员账号 */
    @PostConstruct
    public void initAdmin() {
        AppProperties.Auth.InitAdmin cfg = properties.getAuth().getInitAdmin();
        if (!cfg.isEnabled() || userMapper.count() > 0) {
            return;
        }
        SysUser user = new SysUser();
        user.setId(IdGen.uuid());
        user.setAccount(cfg.getAccount());
        user.setName(cfg.getName());
        user.setPassword(md5(cfg.getPassword()));
        user.setStatus("1");
        user.setCreateDate(new Date());
        userMapper.insert(user);
        log.info("已初始化管理员账号: {}", cfg.getAccount());
    }

    /** 登录校验，成功返回 token 与用户信息 */
    public Map<String, Object> login(String account, String password) {
        if (account == null || account.isEmpty() || password == null || password.isEmpty()) {
            throw new BizException("账号和密码不能为空");
        }
        SysUser user = userMapper.findByAccount(account);
        if (user == null || !md5(password).equals(user.getPassword())) {
            throw new BizException("账号或密码错误");
        }
        if (!"1".equals(user.getStatus())) {
            throw new BizException("账号已被禁用，请联系管理员");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getAccount(), user.getPassword());
        user.setPassword(null);
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("user", user);
        return result;
    }

    /** 修改当前登录用户密码 */
    public void changePassword(SysUser currentUser, String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isEmpty() || newPassword == null || newPassword.isEmpty()) {
            throw new BizException("参数不能为空");
        }
        SysUser latest = userMapper.findById(currentUser.getId());
        if (latest == null) {
            throw new BizException("用户不存在");
        }
        if (!md5(oldPassword).equals(latest.getPassword())) {
            throw new BizException("旧密码错误");
        }
        if (newPassword.length() < 6) {
            throw new BizException("新密码长度不能少于 6 位");
        }
        userMapper.updatePassword(currentUser.getId(), md5(newPassword), currentUser.getAccount(), new Date());
    }

    public static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(text.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new BizException("MD5 摘要算法不可用", e);
        }
    }
}
