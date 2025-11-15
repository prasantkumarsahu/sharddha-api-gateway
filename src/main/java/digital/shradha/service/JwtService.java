package digital.shradha.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class JwtService {

    @Value("${jwt.secret}")
    private String key;

    private SecretKey secretKey;

    @PostConstruct
    void init() {
        this.secretKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(key));
    }

    public boolean validateToken(String token) {
        try {
            String usernameOrEmail = extractUsernameOrEmail(token);
            if (!StringUtils.hasText(usernameOrEmail))
                throw new IllegalArgumentException("Token username mismatch");

            return true;
        } catch (ExpiredJwtException e) {
            throw new IllegalArgumentException("JWT token has expired", e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid JWT token", e);
        }
    }

    public String extractUsernameOrEmail(String token) {
        return getClaims(token).getSubject();
    }

    public Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
