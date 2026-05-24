package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that enforces Bearer API-key authentication on drain endpoints.
 *
 * <p>This filter is only registered when {@code dlq-drain.api-key} is configured
 * (see {@link es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainInfrastructureConfig}).
 * When no key is configured, reachability is enforced at the Kubernetes NetworkPolicy level.</p>
 *
 * <p>The caller must send the key in the {@code Authorization} header:</p>
 * <pre>Authorization: Bearer &lt;api-key&gt;</pre>
 *
 * <p>Management / actuator endpoints on the separate management port are on a different
 * servlet context and are not affected by this filter.</p>
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final String expectedBearerHeader;

    public ApiKeyAuthFilter(String apiKey) {
        this.expectedBearerHeader = BEARER_PREFIX + apiKey;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (!expectedBearerHeader.equals(authHeader)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"AUTHENTICATION_ERROR\",\"message\":\"Missing or invalid API key\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}

