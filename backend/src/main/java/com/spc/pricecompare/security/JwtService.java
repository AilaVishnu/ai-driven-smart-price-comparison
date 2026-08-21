package com.spc.pricecompare.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and verifies the stateless JWTs used for authentication.
 *
 * <p>Signing is HMAC-SHA256, which needs at least 256 bits of key. That is
 * checked at startup rather than at first login: a configuration mistake should
 * stop the application immediately, not surface as a runtime failure the first
 * time somebody tries to sign in.
 */
@Service
@Slf4j
public class JwtService {

    /** HS256 requires a key of at least 256 bits. */
    private static final int MIN_SECRET_BYTES = 32;

    private final String secret;
    private final long expirationMs;

    private SecretKey key;

    public JwtService(@Value("${security.jwt.secret}") String secret,
                      @Value("${security.jwt.expiration-ms:86400000}") long expirationMs) {
        this.secret = secret;
        this.expirationMs = expirationMs;
    }

    @PostConstruct
    void init() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "security.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " characters. Set the JWT_SECRET environment variable.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        if (secret.contains("dev-only") || secret.startsWith("ZGV2LW9ubHkt")) {
            log.warn("Using the built-in development JWT secret. Set JWT_SECRET before deploying anywhere real.");
        }
    }

    public String generateToken(String email, Long userId, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(email)
                .claim("uid", userId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * Extracts the subject if the token is valid.
     *
     * @return the email, or empty for any token that is malformed, expired or
     *         wrongly signed - the caller only needs to know it cannot be trusted
     */
    public Optional<String> extractEmail(String token) {
        return parse(token).map(Claims::getSubject);
    }

    public Optional<Claims> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public long expirationMs() {
        return expirationMs;
    }
}
