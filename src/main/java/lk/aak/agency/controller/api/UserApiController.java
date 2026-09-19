package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.UserRequest;
import lk.aak.agency.dto.api.UserResponse;
import lk.aak.agency.model.SystemUser;
import lk.aak.agency.service.UserManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/** ADMIN-only - enforced at the security-filter level in ApiSecurityConfig (/api/v1/users/**). */
@RestController
@RequestMapping("/api/v1/users")
public class UserApiController {

    private final UserManagementService userManagementService;

    public UserApiController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping
    public java.util.List<UserResponse> list() {
        return userManagementService.getAllUsers().stream().map(UserResponse::new).toList();
    }

    @GetMapping("/{id}")
    public UserResponse getById(@PathVariable Long id) {
        return userManagementService.getUserById(id)
                .map(UserResponse::new)
                .orElseThrow(() -> new NoSuchElementException("User not found."));
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {

        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("Password is required for a new user.");
        }

        SystemUser user = new SystemUser();
        user.setUsername(request.getUsername());
        user.setFullName(request.getFullName());
        user.setRole(request.getRole());
        user.setEnabled(request.isEnabled());

        userManagementService.createUser(user, request.getPassword());

        return ResponseEntity.status(HttpStatus.CREATED).body(new UserResponse(user));
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UserRequest request) {

        userManagementService.updateUser(
                id,
                request.getFullName(),
                request.getRole(),
                request.isEnabled(),
                request.getPassword()
        );

        return userManagementService.getUserById(id)
                .map(UserResponse::new)
                .orElseThrow(() -> new NoSuchElementException("User not found."));
    }
}
