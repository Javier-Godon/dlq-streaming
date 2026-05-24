package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApiKeyAuthFilter}.
 *
 * <p>The filter enforces that every request to `/drain/**` carries a valid
 * {@code Authorization: Bearer <api-key>} header. These tests verify the
 * three cases the filter must handle:</p>
 * <ol>
 *   <li>Missing {@code Authorization} header → 401 Unauthorized.</li>
 *   <li>Wrong API key (or wrong prefix) → 401 Unauthorized.</li>
 *   <li>Correct {@code Bearer <key>} header → request continues normally (filter chain invoked).</li>
 * </ol>
 *
 * <p>The filter only protects drain endpoints. Actuator/health endpoints live on a separate
 * management port and are outside this filter's servlet context.</p>
 */
class ApiKeyAuthFilterTest {

    private static final String VALID_API_KEY = "super-secret-key-12345";

    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(VALID_API_KEY);
    }

    @Test
    void returns401WhenAuthorizationHeaderIsMissing() throws Exception {
        var request = new MockHttpServletRequest("POST", "/drain/trigger");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTHENTICATION_ERROR");
        assertThat(chain.getRequest()).isNull(); // chain was NOT invoked
    }

    @Test
    void returns401WhenBearerTokenIsWrong() throws Exception {
        var request = new MockHttpServletRequest("POST", "/drain/trigger");
        request.addHeader("Authorization", "Bearer wrong-key");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTHENTICATION_ERROR");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void returns401WhenAuthorizationSchemeIsNotBearer() throws Exception {
        // Some callers may send "Basic ..." or just the raw key without Bearer prefix.
        var request = new MockHttpServletRequest("POST", "/drain/trigger");
        request.addHeader("Authorization", VALID_API_KEY); // missing "Bearer " prefix
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void allowsRequestWhenBearerTokenIsCorrect() throws Exception {
        var request = new MockHttpServletRequest("POST", "/drain/trigger");
        request.addHeader("Authorization", "Bearer " + VALID_API_KEY);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200); // default; filter did not write a 401
        assertThat(chain.getRequest()).isNotNull(); // chain WAS invoked
    }

    @Test
    void responseIncludesJsonContentTypeOnRejection() throws Exception {
        var request = new MockHttpServletRequest("POST", "/drain/trigger");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
    }
}

