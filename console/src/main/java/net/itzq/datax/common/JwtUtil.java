package net.itzq.datax.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import net.itzq.datax.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final AppProperties properties;
    private final SecretKey secretKey;

    public JwtUtil(AppProperties properties) {
        this.properties = properties;
        // 在构造时一次性生成 SecretKey，避免每次请求重复转换
        byte[] keyBytes = properties.getAuth().getSecret()
                .getBytes(StandardCharsets.UTF_8);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String userId, String account, String passwordMd5) {
        AppProperties.Auth auth = properties.getAuth();
        long expireMs = auth.getExpireHours() * 3600_000L;
        return Jwts.builder()
                .subject(userId)
                .claim("account", account)
                .claim("pw", passwordMd5)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expireMs))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
