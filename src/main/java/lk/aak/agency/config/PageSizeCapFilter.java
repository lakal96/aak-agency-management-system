package lk.aak.agency.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Caps the "size" (page size) query parameter of every API request at 200, in one place, so no list
 * endpoint can be asked for 100,000 rows at once. Values that are not numbers are left alone (the
 * endpoint answers them with a clear 400), and zero or negative sizes are left to the endpoints.
 */
@Component
public class PageSizeCapFilter extends OncePerRequestFilter {

    public static final int MAX_PAGE_SIZE = 200;
    private static final String PARAMETER = "size";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String size = request.getParameter(PARAMETER);
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/") || size == null || exceedsCap(size) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new CappedRequest(request), response);
    }

    /** The capped text when the value is a number above the cap, else null. */
    private static String exceedsCap(String value) {
        try {
            return new BigInteger(value.trim()).compareTo(BigInteger.valueOf(MAX_PAGE_SIZE)) > 0
                    ? String.valueOf(MAX_PAGE_SIZE) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String capped(String value) {
        if (value == null) {
            return null;
        }
        String cappedValue = exceedsCap(value);
        return cappedValue == null ? value : cappedValue;
    }

    private static final class CappedRequest extends HttpServletRequestWrapper {

        CappedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getParameter(String name) {
            String value = super.getParameter(name);
            return PARAMETER.equals(name) ? capped(value) : value;
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] values = super.getParameterValues(name);
            if (!PARAMETER.equals(name) || values == null) {
                return values;
            }
            String[] result = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                result[i] = capped(values[i]);
            }
            return result;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> map = new LinkedHashMap<>(super.getParameterMap());
            if (map.containsKey(PARAMETER)) {
                map.put(PARAMETER, getParameterValues(PARAMETER));
            }
            return map;
        }
    }
}
