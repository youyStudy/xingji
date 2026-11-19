package com.xingji.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

/**
 * JWT 签发 / 解析。subject 存的是 userId（不存手机号，避免 PII 泄露、且手机号可变）。
 * 最小可跑版，你可按需重写。
 */
@Component
public class JwtTokenProvider {

    private final Key key;
    private final long expireMillis;

    public JwtTokenProvider(@Value("${xingji.jwt.secret}") String secret,
                            @Value("${xingji.jwt.expire-minutes:1440}") long expireMinutes) {
        // HS256 要求密钥 >= 256bit(32字节)，太短会抛 WeakKeyException
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireMinutes * 60 * 1000L;
    }

    /** 登录成功后签发 token。 */
    public String createToken(Integer userId) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expireMillis))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** 从 token 解析出 userId，供拦截器使用。 */
    public Integer parseUserId(String token) {
        String subject = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
        return Integer.valueOf(subject);
    }
}
