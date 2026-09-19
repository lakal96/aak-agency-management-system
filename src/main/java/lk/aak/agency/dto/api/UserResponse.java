package lk.aak.agency.dto.api;

import lk.aak.agency.model.SystemUser;

public class UserResponse {

    private final Long id;
    private final String username;
    private final String fullName;
    private final String role;
    private final boolean enabled;

    public UserResponse(SystemUser user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.fullName = user.getFullName();
        this.role = user.getRole();
        this.enabled = user.isEnabled();
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
}
