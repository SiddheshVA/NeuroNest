package com.neuronest.backend.pro;

import com.neuronest.backend.auth.ClerkTokenVerifier;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import com.neuronest.backend.professional.ProfessionalAccountStatus;
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

// Authenticates /api/pro/** from a Clerk bearer token, and nothing else: admin tokens and the user
// cookie are not accepted. Reuses ClerkTokenVerifier (JWKS, issuer, expiry) but not the end-user
// mapping onto the users table. Not a @Component: it is added only to the /api/pro/** security
// chain (SecurityConfig), so it cannot affect any other route.
public class ProAuthFilter extends OncePerRequestFilter {

    public static final String ROLE = "PROFESSIONAL";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ClerkTokenVerifier clerkTokenVerifier;
    private final ProfessionalAccountRepository accounts;

    public ProAuthFilter(ClerkTokenVerifier clerkTokenVerifier, ProfessionalAccountRepository accounts) {
        this.clerkTokenVerifier = clerkTokenVerifier;
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            ClerkTokenVerifier.ClerkClaims claims = clerkTokenVerifier.verify(header.substring(BEARER_PREFIX.length()));
            if (claims != null && claims.subject() != null && !claims.subject().isBlank()) {
                // A disabled account is refused on every /api/pro route, registration included.
                boolean disabled = accounts.findByClerkUserId(claims.subject())
                        .map(account -> account.getStatus() == ProfessionalAccountStatus.DISABLED)
                        .orElse(false);
                if (disabled) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"This professional account has been disabled. "
                            + "Please contact NeuroNest support.\"}");
                    return;
                }
                var authentication = new UsernamePasswordAuthenticationToken(new ProPrincipal(claims.subject()), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        // Anything else stays unauthenticated, and the chain's hasRole rule answers 401.
        filterChain.doFilter(request, response);
    }
}
