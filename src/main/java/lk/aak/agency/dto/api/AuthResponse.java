package lk.aak.agency.dto.api;

/** Returned by /api/auth/login and /api/auth/refresh; the refresh token itself travels as an httpOnly cookie. */
public class AuthResponse {

    private final String accessToken;
    private final long expiresInSeconds;
    private final String username;
    private final String fullName;
    private final String role;

    public AuthResponse(String accessToken, long expiresInSeconds, String username, String fullName, String role) {
        this.accessToken = accessToken;
        this.expiresInSeconds = expiresInSeconds;
        this.username = username;
        this.fullName = fullName;
        this.role = role;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
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
}
