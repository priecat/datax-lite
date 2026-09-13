package net.itzq.datax.config;

import net.itzq.datax.common.JwtUtil;
import net.itzq.datax.entity.SysUser;
import net.itzq.datax.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Objects;

/**
 * 登录认证拦截器：除登录接口外，所有 /api/** 接口必须携带有效 token，
 * 未登录返回 401，防止功能接口被匿名调用。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 当前登录用户在 request attribute 中的 key */
    public static final String CURRENT_USER = "currentUser";

    private final JwtUtil jwtUtil;
    private final SysUserMapper userMapper;

    public AuthInterceptor(JwtUtil jwtUtil, SysUserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行浏览器预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Claims claims = jwtUtil.parse(header.substring(7));
            if (claims != null) {
                SysUser user = userMapper.findById(claims.getSubject());
                // 用户被删除/禁用/修改密码后，旧 token 立即失效
                if (user != null && "1".equals(user.getStatus())
                        && Objects.equals(claims.get("pw", String.class), user.getPassword())) {
                    user.setPassword(null);
                    request.setAttribute(CURRENT_USER, user);
                    return true;
                }
            }
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"未登录或登录已过期\"}");
        return false;
    }
}
