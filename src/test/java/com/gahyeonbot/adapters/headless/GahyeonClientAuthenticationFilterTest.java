package com.gahyeonbot.adapters.headless;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.gahyeonbot.application.identity.IdentityLinkUseCase;
import com.gahyeonbot.core.identity.ActorId;

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

    @Test
    void accountCredentialAuthenticatesRemoteRequestAndBindsActor() throws Exception {
        IdentityLinkUseCase links = mock(IdentityLinkUseCase.class);
        when(links.authenticateDesktopCredential("account-secret")).thenReturn(new ActorId(42));
        var filter = new GahyeonClientAuthenticationFilter("deployment-secret", links);
        var accepted = request("192.0.2.1");
        accepted.addHeader(GahyeonClientAuthenticationFilter.ACCOUNT_TOKEN_HEADER, "account-secret");
        var chain = new MockFilterChain();
        filter.doFilter(accepted, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(chain.getRequest().getAttribute(
                GahyeonClientAuthenticationFilter.AUTHENTICATED_ACTOR_ATTRIBUTE))
                .isEqualTo(new ActorId(42));

        var denied = request("192.0.2.1");
        denied.addHeader(GahyeonClientAuthenticationFilter.ACCOUNT_TOKEN_HEADER, "wrong");
        var response = new MockHttpServletResponse();
        filter.doFilter(denied, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);

        var headless = new MockHttpServletRequest("POST", "/api/gahyeon/headless/conversations");
        headless.setContextPath("/api");
        headless.setRemoteAddr("192.0.2.1");
        headless.addHeader(GahyeonClientAuthenticationFilter.ACCOUNT_TOKEN_HEADER, "account-secret");
        var headlessResponse = new MockHttpServletResponse();
        filter.doFilter(headless, headlessResponse, new MockFilterChain());
        assertThat(headlessResponse.getStatus()).isEqualTo(401);
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        var request = new MockHttpServletRequest("GET", "/api/gahyeon/desktop/events");
        request.setContextPath("/api");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
