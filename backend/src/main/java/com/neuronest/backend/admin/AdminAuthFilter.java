package com.neuronest.backend.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// Authenticates admin dashboard requests from an admin Bearer token. Deliberately not a @Component:
// it is added only to the /api/admin/** security chain (SecurityConfig), never as a global servlet
// filter, so it cannot affect any other route. Anything but a valid admin token leaves the request
// unauthenticated, and the chain's hasRole("ADMIN") rule answers 401.
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AdminJwtService adminJwtService;

    public AdminAuthFilter(AdminJwtService adminJwtService) {
        this.adminJwtService = adminJwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            adminJwtService.verify(header.substring(BEARER_PREFIX.length())).ifPresent(username -> {
                var authentication = new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + AdminJwtService.ROLE)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }

        filterChain.doFilter(request, response);
    }
}
