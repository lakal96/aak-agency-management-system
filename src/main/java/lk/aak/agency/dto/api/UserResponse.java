package lk.aak.agency.dto.api;

import lk.aak.agency.model.SystemUser;

public class UserResponse {

    private final Long id;
    private final String username;
    private final String fullName;
    private final String role;
    private final boolean enabled;
    private final boolean locked;

    public UserResponse(SystemUser user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.fullName = user.getFullName();
        this.role = user.getRole();
        this.enabled = user.isEnabled();
        this.locked = user.getLockedUntil() != null
                && lk.aak.agency.util.BusinessTime.now().isBefore(user.getLockedUntil());
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getFullName() {
        return fullName;
    }

    public String getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True while the account is locked after too many wrong passwords (use POST /{id}/unlock). */
    public boolean isLocked() {
        return locked;
    }
}
