package com.neuronest.backend.config;

import com.neuronest.backend.admin.AdminAuthFilter;
import com.neuronest.backend.admin.AdminJwtService;
import com.neuronest.backend.auth.ClerkAuthFilter;
import com.neuronest.backend.auth.ClerkTokenVerifier;
import com.neuronest.backend.auth.JwtAuthFilter;
import com.neuronest.backend.pro.ProAuthFilter;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Admin dashboard API. Matched before the main chain and handled only here: Clerk and cookie
    // auth are not part of this chain, so the only accepted credential is an admin token.
    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(HttpSecurity http, AdminJwtService adminJwtService) throws Exception {
        http
                .securityMatcher("/api/admin/**")
                .csrf(csrf -> csrf.disable())
                // Applies WebConfig's CORS mapping here too, so 401s from this chain still carry
                // CORS headers and the dashboard can read them instead of seeing a network error.
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/login").permitAll()
                        .anyRequest().hasRole(AdminJwtService.ROLE))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJsonError(response, 401, "Authentication required."))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJsonError(response, 403, "Access denied.")))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .addFilterBefore(new AdminAuthFilter(adminJwtService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // Professional self-service API. Only a valid Clerk token is accepted here: admin tokens and the
    // user cookie are not part of this chain, and Clerk users are not mapped onto the users table.
    @Bean
    @Order(2)
    public SecurityFilterChain proFilterChain(HttpSecurity http, ClerkTokenVerifier clerkTokenVerifier,
                                              ProfessionalAccountRepository accounts) throws Exception {
        http
                .securityMatcher("/api/pro/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().hasRole(ProAuthFilter.ROLE))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJsonError(response, 401, "Authentication required."))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJsonError(response, 403, "Access denied.")))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .addFilterBefore(new ProAuthFilter(clerkTokenVerifier, accounts), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // Booking requests of signed-in users. Only a verified Clerk token is accepted: ClerkAuthFilter maps
    // it onto the user's own users row (creating it on first use with the verified Clerk email). The
    // user cookie is not part of this chain, and an admin token is not a Clerk token, so neither works.
    @Bean
    @Order(3)
    public SecurityFilterChain bookingFilterChain(HttpSecurity http, ClerkAuthFilter clerkAuthFilter) throws Exception {
        http
                .securityMatcher("/api/bookings/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJsonError(response, 401, "Authentication required."))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJsonError(response, 403, "Access denied.")))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .addFilterBefore(clerkAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // ClerkAuthFilter and JwtAuthFilter are @Components, so Spring Boot would also register them as
    // global servlet filters that run after every security chain. They already run inside the main
    // chain below; the duplicate registration only reached /api/admin/** and /api/pro/**, where it
    // could map a professional's Clerk token onto the end-user table. Turn it off.
    @Bean
    public FilterRegistrationBean<ClerkAuthFilter> clerkAuthFilterServletRegistration(ClerkAuthFilter filter) {
        FilterRegistrationBean<ClerkAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterServletRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ClerkAuthFilter clerkAuthFilter,
                                            JwtAuthFilter jwtAuthFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // CORS preflight (OPTIONS) carries no Authorization header — it must
                        // never be subject to a hasRole(...) check, or the browser's preflight
                        // fails before Spring MVC's CORS headers are ever attached, and every
                        // authenticated fetch() throws "Failed to fetch" despite a valid token.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Anonymous-by-default is a product requirement: every other route
                        // stays open to guests; controllers check the user where needed.
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJsonError(response, 401, "Authentication required."))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJsonError(response, 403, "Access denied.")))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(clerkAuthFilter, JwtAuthFilter.class);

        return http.build();
    }

    private static void writeJsonError(jakarta.servlet.http.HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
