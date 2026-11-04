package lk.aak.agency.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lk.aak.agency.model.SystemUser;
import lk.aak.agency.repository.SystemUserRepository;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Reads the "Authorization: Bearer <token>" header and populates the security context.
 *
 * <p>The token only proves who the caller is. What they may do is decided by the user record as
 * it is right now, looked up on every request (nothing is cached): a user who has been disabled
 * or deleted loses access immediately instead of when the 15-minute token runs out, and
 * a changed role takes effect at once - the role inside the token is never trusted. A failed-login
 * lock deliberately does NOT end a session already in progress (a stranger guessing the owner's
 * password must not be able to evict the owner); it only blocks new logins and refreshes.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final SystemUserRepository systemUserRepository;

    public JwtAuthenticationFilter(JwtService jwtService, SystemUserRepository systemUserRepository) {
        this.jwtService = jwtService;
        this.systemUserRepository = systemUserRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith(BEARER_PREFIX)) {

            String token = header.substring(BEARER_PREFIX.length());

            if (jwtService.isValid(token) && SecurityContextHolder.getContext().getAuthentication() == null) {

                String username = jwtService.extractUsername(token);

                Optional<SystemUser> current = systemUserRepository.findByUsername(username)
                        .filter(SystemUser::isEnabled);

                if (current.isPresent()) {

                    List<SimpleGrantedAuthority> authorities =
                            List.of(new SimpleGrantedAuthority("ROLE_" + current.get().getRole()));

                    var authentication = new UsernamePasswordAuthenticationToken(
                            current.get().getUsername(), null, authorities);

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
