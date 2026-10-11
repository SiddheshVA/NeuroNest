package com.neuronest.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// Verifies a Clerk bearer token and, on success, sets the same kind of
// authentication (principal = local User UUID) that JwtAuthFilter sets from
// the legacy cookie, so AuthUtil.currentUserId(...) and every controller
// stay unchanged. Runs before JwtAuthFilter; any failure here just leaves
// the request to fall through to the cookie path (or guest).
@Component
public class ClerkAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClerkAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final ClerkTokenVerifier clerkTokenVerifier;
    private final ClerkUserService clerkUserService;

    public ClerkAuthFilter(ClerkTokenVerifier clerkTokenVerifier, ClerkUserService clerkUserService) {
        this.clerkTokenVerifier = clerkTokenVerifier;
        this.clerkUserService = clerkUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                String token = header.substring(BEARER_PREFIX.length());
                ClerkTokenVerifier.ClerkClaims claims = clerkTokenVerifier.verify(token);
                if (claims != null) {
                    User user = clerkUserService.provision(claims.subject(), claims.email(), claims.name());
                    if (user != null) {
                        var authentication = new UsernamePasswordAuthenticationToken(user.getId(), null, List.of());
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            } catch (Exception e) {
                log.debug("Clerk authentication failed, proceeding as guest: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}
