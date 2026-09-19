package lk.aak.agency.controller.api;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lk.aak.agency.dto.api.AuthResponse;
import lk.aak.agency.dto.api.ChangePasswordRequest;
import lk.aak.agency.dto.api.LoginRequest;
import lk.aak.agency.model.SystemUser;
import lk.aak.agency.repository.SystemUserRepository;
import lk.aak.agency.security.LoginAttemptService;
import lk.aak.agency.security.jwt.JwtService;
import lk.aak.agency.security.jwt.RefreshTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthApiController {

    private static final String REFRESH_COOKIE_NAME = "refreshToken";

    private final AuthenticationManager authenticationManager;
    private final LoginAttemptService loginAttemptService;
    private final SystemUserRepository systemUserRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final boolean cookieSecure;

    public AuthApiController(
            AuthenticationManager authenticationManager,
            LoginAttemptService loginAttemptService,
            SystemUserRepository systemUserRepository,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            PasswordEncoder passwordEncoder,
            @Value("${app.security.cookie-secure}") boolean cookieSecure) {

        this.authenticationManager = authenticationManager;
        this.loginAttemptService = loginAttemptService;
        this.systemUserRepository = systemUserRepository;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {

        if (loginAttemptService.isLocked(request.getUsername())) {
            throw new LockedException("This account is temporarily locked due to repeated failed logins.");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
        } catch (BadCredentialsException | DisabledException exception) {
            loginAttemptService.recordFailure(request.getUsername());
            throw new BadCredentialsException("Invalid username or password.");
        }

        loginAttemptService.recordSuccess(request.getUsername());

        SystemUser user = systemUserRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password."));

        return issueTokens(user, response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response) {

        String username = refreshTokenService.consume(refreshToken)
                .orElseThrow(() -> new BadCredentialsException("Refresh token is invalid or has expired."));

        SystemUser user = systemUserRepository.findByUsername(username)
                .orElseThrow(() -> new BadCredentialsException("Refresh token is invalid or has expired."));

        if (!user.isEnabled()) {
            throw new DisabledException("This account has been disabled.");
        }

        return issueTokens(user, response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response) {

        refreshTokenService.revoke(refreshToken);
        clearRefreshCookie(response);

        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.GetMapping("/me")
    public AuthResponse me(org.springframework.security.core.Authentication authentication) {

        SystemUser user = systemUserRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new BadCredentialsException("User account was not found."));

        return new AuthResponse(null, 0, user.getUsername(), user.getFullName(), user.getRole());
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @jakarta.validation.Valid @RequestBody ChangePasswordRequest request,
            org.springframework.security.core.Authentication authentication) {

        SystemUser user = systemUserRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new BadCredentialsException("User account was not found."));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new BadCredentialsException("Current password is incorrect.");
        }

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("New password and confirmation do not match.");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        systemUserRepository.save(user);

        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<AuthResponse> issueTokens(SystemUser user, HttpServletResponse response) {

        String accessToken = jwtService.generateAccessToken(user.getUsername(), user.getRole());
        String refreshToken = refreshTokenService.issue(user.getUsername());

        setRefreshCookie(response, refreshToken);

        return ResponseEntity.status(HttpStatus.OK).body(new AuthResponse(
                accessToken,
                jwtService.accessTokenExpirySeconds(),
                user.getUsername(),
                user.getFullName(),
                user.getRole()
        ));
    }

    private void setRefreshCookie(HttpServletResponse response, String refreshToken) {

        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(java.time.Duration.ofDays(refreshTokenService.refreshTokenDays()))
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {

        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(0)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
