package com.gahyeonbot.adapters.headless;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class GahyeonClientAuthenticationFilterTest {
    @Test
    void allowsLoopbackWhenNoTokenIsConfigured() throws Exception {
        var chain = new MockFilterChain();
        new GahyeonClientAuthenticationFilter("").doFilter(
                request("127.0.0.1"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void deniesRemoteAccessWhenNoTokenIsConfigured() throws Exception {
        var response = new MockHttpServletResponse();
        new GahyeonClientAuthenticationFilter("").doFilter(
                request("192.0.2.1"), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void requiresMatchingBearerTokenWhenConfigured() throws Exception {
        var filter = new GahyeonClientAuthenticationFilter("secret-token");
        var denied = new MockHttpServletResponse();
        filter.doFilter(request("127.0.0.1"), denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(401);

        var acceptedRequest = request("192.0.2.1");
        acceptedRequest.addHeader("Authorization", "Bearer secret-token");
        var chain = new MockFilterChain();
        filter.doFilter(acceptedRequest, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void ignoresNonGahyeonEndpoints() throws Exception {
        var filter = new GahyeonClientAuthenticationFilter("secret-token");
        var request = new MockHttpServletRequest("GET", "/api/health");
        request.setContextPath("/api");
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        var request = new MockHttpServletRequest("GET", "/api/gahyeon/desktop/events");
        request.setContextPath("/api");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
