package lk.aak.agency.service;

import lk.aak.agency.model.Employee;
import lk.aak.agency.model.SystemUser;
import lk.aak.agency.repository.SystemUserRepository;
import lk.aak.agency.security.jwt.RefreshTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Keeps a person's login in step with their employee record.
 *
 * <p>Choice made here: setting an employee INACTIVE switches their login off and signs them out
 * everywhere, but setting them ACTIVE again does NOT switch the login back on. Re-enabling a
 * login is a deliberate act for the owner, done on the Users page - a mistaken click on an
 * employee form must never quietly give access back to someone who left.
 */
@Service
public class EmployeeLoginAccessService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmployeeLoginAccessService.class);

    private final SystemUserRepository systemUserRepository;
    private final RefreshTokenService refreshTokenService;
    private final AuditLogService auditLogService;

    public EmployeeLoginAccessService(
            SystemUserRepository systemUserRepository,
            RefreshTokenService refreshTokenService,
            AuditLogService auditLogService) {

        this.systemUserRepository = systemUserRepository;
        this.refreshTokenService = refreshTokenService;
        this.auditLogService = auditLogService;
    }

    /**
     * Call after an employee has been saved. Does nothing unless the employee has just become
     * INACTIVE (previousStatus is what they were before this save; null for a brand new record).
     */
    @Transactional
    public void onEmployeeSaved(String previousStatus, Employee saved) {

        boolean nowInactive = "INACTIVE".equalsIgnoreCase(saved.getStatus());
        boolean wasInactive = "INACTIVE".equalsIgnoreCase(previousStatus);
        String login = saved.getSystemUsername();

        if (!nowInactive || wasInactive || login == null || login.isBlank()) {
            return;
        }

        List<SystemUser> users = systemUserRepository.findAllByUsernameIgnoreCase(login.trim());

        for (SystemUser user : users) {

            refreshTokenService.revokeAllFor(user.getUsername());

            if (!user.isEnabled()) {
                continue;
            }

            if ("ADMIN".equals(user.getRole()) && countOtherEnabledAdmins(user) == 0) {
                // Never lock the business out of its own system because of an employee form.
                LOGGER.warn("Employee \"{}\" was set INACTIVE but login \"{}\" is the last active owner login, "
                        + "so it was left switched on.", saved.getFullName(), user.getUsername());
                continue;
            }

            user.setEnabled(false);
            systemUserRepository.save(user);

            auditLogService.record(
                    "USER_UPDATED", "SystemUser", user.getId(),
                    "Updated user \"" + user.getUsername() + "\": disabled because employee \""
                            + saved.getFullName() + "\" was set to INACTIVE"
            );
        }
    }

    private long countOtherEnabledAdmins(SystemUser excluding) {
        return systemUserRepository.findAll().stream()
                .filter(user -> !user.getId().equals(excluding.getId()))
                .filter(SystemUser::isEnabled)
                .filter(user -> "ADMIN".equals(user.getRole()))
                .count();
    }
}
