package lk.aak.agency.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/** Issues and validates short-lived JWT access tokens for the REST API. */
@Service
public class JwtService {

    private static final String ROLE_CLAIM = "role";

    // Must match application.properties' app.jwt.secret default exactly - if this literal value
    // is still in use while the app is deployed behind HTTPS (cookie-secure=true), someone forgot
    // to set JWT_SECRET, and every access token is forgeable by anyone who reads this source file.
    private static final String INSECURE_DEFAULT_SECRET =
            "dev-only-secret-do-not-use-in-production-please-change-me-32bytes";

    private final SecretKey signingKey;
    private final long accessTokenMinutes;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-minutes}") long accessTokenMinutes,
            @Value("${app.security.cookie-secure}") boolean cookieSecure) {

        if (cookieSecure && INSECURE_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "Refusing to start: app.security.cookie-secure=true (this looks like a real "
                            + "deployment) but JWT_SECRET is still the insecure dev-only default. "
                            + "Set a real, random, >=32 byte JWT_SECRET environment variable."
            );
        }

        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public String generateAccessToken(String username, String role) {

        Instant now = Instant.now();

        return Jwts.builder()
                .subject(username)
                .claim(ROLE_CLAIM, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenMinutes, ChronoUnit.MINUTES)))
                .signWith(signingKey)
                .compact();
    }

    public long accessTokenExpirySeconds() {
        return accessTokenMinutes * 60;
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public List<String> extractRoles(String token) {
        String role = parseClaims(token).get(ROLE_CLAIM, String.class);
        return role == null ? List.of() : List.of(role);
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
