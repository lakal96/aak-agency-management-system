package lk.aak.agency.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * After login, sends the user back to the shop QR page they scanned instead of always landing on
 * the dashboard. The redirect target is checked against a strict allow-list (not just a "starts
 * with" prefix) so this can never be turned into an open redirect.
 */
@Component
public class ScanRedirectAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final Pattern ALLOWED_REDIRECT =
            Pattern.compile("^/customers/scan/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        String redirect = request.getParameter("redirect");
        String target = (redirect != null && ALLOWED_REDIRECT.matcher(redirect).matches())
                ? redirect
                : "/";

        response.sendRedirect(request.getContextPath() + target);
    }
}
