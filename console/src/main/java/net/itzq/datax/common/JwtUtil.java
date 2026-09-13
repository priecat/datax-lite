package net.itzq.datax.common;

import net.itzq.datax.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：密钥与有效期配置于 application.yml 的 datax-console.auth
 */
@Component
public class JwtUtil {

    private final AppProperties properties;

    public JwtUtil(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * 签发 token。payload 中携带签发时的密码摘要，
     * 用户修改密码/被重置密码/被禁用后，旧 token 自动失效。
     */
    public String generateToken(String userId, String account, String passwordMd5) {
        AppProperties.Auth auth = properties.getAuth();
        long expireMs = auth.getExpireHours() * 3600_000L;
        return Jwts.builder()
                .setSubject(userId)
                .claim("account", account)
                .claim("pw", passwordMd5)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expireMs))
                .signWith(SignatureAlgorithm.HS256, auth.getSecret().getBytes(StandardCharsets.UTF_8))
                .compact();
    }

    /** 解析并验签，无效或过期返回 null */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .setSigningKey(properties.getAuth().getSecret().getBytes(StandardCharsets.UTF_8))
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            return null;
        }
    }
}
