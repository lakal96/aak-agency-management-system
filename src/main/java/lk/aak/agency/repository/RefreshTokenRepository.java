package lk.aak.agency.repository;

import lk.aak.agency.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    java.util.List<RefreshToken> findByUsernameAndRevokedFalse(String username);

    void deleteAllByExpiresAtBefore(LocalDateTime cutoff);
}
