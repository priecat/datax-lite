package net.itzq.datax.controller;

import net.itzq.datax.common.R;
import net.itzq.datax.config.AuthInterceptor;
import net.itzq.datax.entity.SysUser;
import net.itzq.datax.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录（无需 token，唯一放行的业务接口） */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return R.ok(authService.login(body.get("account"), body.get("password")));
    }

    /** 当前登录用户信息 */
    @GetMapping("/user_info")
    public R<SysUser> userInfo(HttpServletRequest request) {
        return R.ok(currentUser(request));
    }

    /** 修改当前登录用户密码，成功后需重新登录 */
    @PostMapping("/change_password")
    public R<Void> changePassword(@RequestBody Map<String, String> body, HttpServletRequest request) {
        authService.changePassword(currentUser(request), body.get("oldPassword"), body.get("newPassword"));
        return R.ok();
    }

    private SysUser currentUser(HttpServletRequest request) {
        return (SysUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
    }
}
