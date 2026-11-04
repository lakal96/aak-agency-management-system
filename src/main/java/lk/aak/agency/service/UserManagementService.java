package lk.aak.agency.service;

import lk.aak.agency.model.SystemUser;
import lk.aak.agency.repository.SystemUserRepository;
import lk.aak.agency.security.jwt.RefreshTokenService;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class UserManagementService {

    /** The only roles the system knows. Written exactly like this (upper case) - never "fixed" silently. */
    public static final List<String> ROLES = List.of("ADMIN", "OFFICE", "SALES_REP", "DRIVER");

    public static final int MIN_PASSWORD_LENGTH = 8;

    private final SystemUserRepository systemUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final RefreshTokenService refreshTokenService;

    public UserManagementService(
            SystemUserRepository systemUserRepository,
            PasswordEncoder passwordEncoder,
            AuditLogService auditLogService,
            RefreshTokenService refreshTokenService) {

        this.systemUserRepository = systemUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.refreshTokenService = refreshTokenService;
    }

    public static void requireValidRole(String role) {
        if (role == null || !ROLES.contains(role)) {
            throw new IllegalArgumentException(
                    "Role must be exactly one of: " + String.join(", ", ROLES)
                            + " (capital letters, as written here)."
            );
        }
    }

    public static void requireValidPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long."
            );
        }
    }

    public List<SystemUser> getAllUsers() {
        return systemUserRepository.findAll(Sort.by(Sort.Direction.ASC, "username"));
    }

    public Optional<SystemUser> getUserById(Long id) {
        return systemUserRepository.findById(id);
    }

    public void createUser(SystemUser newUser, String rawPassword) {
        String username = newUser.getUsername().trim();

        requireValidRole(newUser.getRole());
        requireValidPassword(rawPassword);

        if (systemUserRepository.existsByUsername(username)) {
            throw new IllegalArgumentException(
                    "A user with the username \"" + username + "\" already exists."
            );
        }

        newUser.setUsername(username);
        newUser.setPassword(passwordEncoder.encode(rawPassword));

        systemUserRepository.save(newUser);

        auditLogService.record(
                "USER_CREATED", "SystemUser", newUser.getId(),
                "Created user \"" + username + "\" with role " + newUser.getRole()
        );
    }

    @Transactional
    public void updateUser(
            Long id,
            String fullName,
            String role,
            boolean enabled,
            String rawPassword) {

        SystemUser user = systemUserRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User account was not found."));

        requireValidRole(role);

        boolean passwordWillChange = rawPassword != null && !rawPassword.isBlank();
        if (passwordWillChange) {
            requireValidPassword(rawPassword);
        }

        boolean wasEnabledAdmin = "ADMIN".equals(user.getRole()) && user.isEnabled();
        boolean staysEnabledAdmin = "ADMIN".equals(role) && enabled;

        if (wasEnabledAdmin && !staysEnabledAdmin && countOtherEnabledAdmins(id) == 0) {
            throw new IllegalArgumentException(
                    "Cannot remove the last active Owner/Administrator account."
            );
        }

        String previousRole = user.getRole();
        boolean previousEnabled = user.isEnabled();
        boolean passwordChanged = rawPassword != null && !rawPassword.isBlank();

        user.setFullName(fullName);
        user.setRole(role);
        user.setEnabled(enabled);

        if (passwordChanged) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            // An admin reset is also the way back in after too many wrong tries.
            user.setFailedAttemptCount(0);
            user.setLockedUntil(null);
        }

        systemUserRepository.save(user);

        // Disabled, demoted or given a new password: whoever holds an old session is signed out.
        if (passwordChanged || (previousEnabled && !enabled) || !previousRole.equals(role)) {
            refreshTokenService.revokeAllFor(user.getUsername());
        }

        StringBuilder details = new StringBuilder("Updated user \"" + user.getUsername() + "\"");

        if (!previousRole.equals(role)) {
            details.append(": role ").append(previousRole).append(" -> ").append(role);
        }

        if (previousEnabled != enabled) {
            details.append(", ").append(enabled ? "enabled" : "disabled");
        }

        if (passwordChanged) {
            details.append(", password reset");
        }

        auditLogService.record(
                "USER_UPDATED", "SystemUser", user.getId(), details.toString()
        );
    }

    /** Clears the failed-login lock so the person can try again straight away. */
    @Transactional
    public void unlockUser(Long id) {

        SystemUser user = systemUserRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found."));

        user.setFailedAttemptCount(0);
        user.setLockedUntil(null);
        systemUserRepository.save(user);

        auditLogService.record(
                "USER_UNLOCKED", "SystemUser", user.getId(),
                "Unlocked user \"" + user.getUsername() + "\""
        );
    }

    private long countOtherEnabledAdmins(Long excludingId) {
        return getAllUsers().stream()
                .filter(user -> !user.getId().equals(excludingId))
                .filter(SystemUser::isEnabled)
                .filter(user -> "ADMIN".equals(user.getRole()))
                .count();
    }
}

