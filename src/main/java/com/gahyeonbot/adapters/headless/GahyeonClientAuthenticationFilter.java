package com.gahyeonbot.adapters.headless;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Protects every opt-in Gahyeon client API without coupling controllers to auth. */
@Component
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
public class GahyeonClientAuthenticationFilter extends OncePerRequestFilter {
    private final byte[] configuredToken;

    public GahyeonClientAuthenticationFilter(
            @Value("${gahyeon.client-auth.token:}") String configuredToken) {
        this.configuredToken = configuredToken == null
                ? new byte[0]
                : configuredToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/gahyeon/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (configuredToken.length == 0) {
            if (isLoopback(request.getRemoteAddr())) {
                filterChain.doFilter(request, response);
                return;
            }
            reject(response, HttpServletResponse.SC_FORBIDDEN,
                    "Remote Gahyeon clients require GAHYEON_CLIENT_TOKEN");
            return;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        byte[] supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        if (!MessageDigest.isEqual(configuredToken, supplied)) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid Gahyeon client token");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address)
                || "::1".equals(address);
    }

    private static void reject(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
