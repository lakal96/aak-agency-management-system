package lk.aak.agency.security.jwt;

import lk.aak.agency.model.RefreshToken;
import lk.aak.agency.repository.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Refresh tokens are random opaque strings (not JWTs) - only their SHA-256 hash is stored,
 * so a stolen database dump can't be used to mint new sessions. Each refresh rotates: the
 * old token is revoked and a new one issued, so a reused/stolen token is detectable.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshTokenDays;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            @Value("${app.jwt.refresh-token-days}") long refreshTokenDays) {

        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenDays = refreshTokenDays;
    }

    @Transactional
    public String issue(String username) {

        String rawToken = randomToken();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUsername(username);
        refreshToken.setTokenHash(hash(rawToken));
        refreshToken.setExpiresAt(LocalDateTime.now().plusDays(refreshTokenDays));

        refreshTokenRepository.save(refreshToken);

        return rawToken;
    }

    /** Validates the raw token, revokes it, and returns the username it belonged to. */
    @Transactional
    public Optional<String> consume(String rawToken) {

        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }

        Optional<RefreshToken> stored = refreshTokenRepository.findByTokenHash(hash(rawToken));

        if (stored.isEmpty()) {
            return Optional.empty();
        }

        RefreshToken refreshToken = stored.get();

        if (refreshToken.isRevoked() || refreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }

        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        return Optional.of(refreshToken.getUsername());
    }

    @Transactional
    public void revoke(String rawToken) {

        if (rawToken == null || rawToken.isBlank()) {
            return;
        }

        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(refreshToken -> {
                    refreshToken.setRevoked(true);
                    refreshTokenRepository.save(refreshToken);
                });
    }

    public long refreshTokenDays() {
        return refreshTokenDays;
    }

    private static String randomToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
