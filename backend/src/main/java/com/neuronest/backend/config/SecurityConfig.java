package com.neuronest.backend.config;

import com.neuronest.backend.auth.ClerkAuthFilter;
import com.neuronest.backend.auth.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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
