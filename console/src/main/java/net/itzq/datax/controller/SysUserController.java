package net.itzq.datax.controller;

import net.itzq.datax.common.R;
import net.itzq.datax.config.AuthInterceptor;
import net.itzq.datax.entity.SysUser;
import net.itzq.datax.service.SysUserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class SysUserController {

    private final SysUserService userService;

    public SysUserController(SysUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public R<List<SysUser>> list() {
        return R.ok(userService.list());
    }

    @PostMapping
    public R<SysUser> create(@RequestBody SysUser user, HttpServletRequest request) {
        SysUser current = currentUser(request);
        user.setCreateBy(current.getAccount());
        user.setUpdateBy(current.getAccount());
        return R.ok(userService.create(user));
    }

    @PutMapping("/{id}")
    public R<SysUser> update(@PathVariable String id, @RequestBody SysUser user, HttpServletRequest request) {
        SysUser current = currentUser(request);
        user.setUpdateBy(current.getAccount());
        return R.ok(userService.update(id, user));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id, HttpServletRequest request) {
        SysUser current = currentUser(request);
        userService.delete(id, current.getId());
        return R.ok();
    }

    /** 重置密码 */
    @PostMapping("/{id}/reset-password")
    public R<Void> resetPassword(@PathVariable String id, @RequestBody Map<String, String> body,
                                 HttpServletRequest request) {
        SysUser current = currentUser(request);
        userService.resetPassword(id, body.get("password"), current.getAccount());
        return R.ok();
    }

    private SysUser currentUser(HttpServletRequest request) {
        return (SysUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
    }
}
