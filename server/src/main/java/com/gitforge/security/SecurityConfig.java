package com.gitforge.security;

import com.gitforge.common.error.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Stateless, token-based security.
 *
 * <p>Endpoint rules here are deliberately coarse — they gate <em>authentication</em>,
 * not ownership. Per-resource authorization (who may edit which repository) lives
 * in the service layer, so a newly added controller cannot accidentally bypass it.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsConfigurationSource corsConfigurationSource;
    private final ObjectMapper objectMapper;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            CorsConfigurationSource corsConfigurationSource,
            ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.corsConfigurationSource = corsConfigurationSource;
        this.objectMapper = objectMapper;
    }

    /**
     * A policy for something that is never a document.
     *
     * <p>Every response from this application is JSON. It loads no script, no
     * stylesheet, no image and no font, and it is never a page. So the policy that
     * fits is the empty one: {@code default-src 'none'} permits nothing, and there
     * is nothing to permit. That is unusually easy to get right here and unusually
     * cheap - a policy that forbids everything cannot break an application that
     * asks for nothing, which is why it is safe to state rather than to tune.
     *
     * <p>What it defends is the case where a response is rendered instead of
     * parsed: a browser opening an API URL directly, an error body reflected
     * somewhere, a content type ignored. {@code frame-ancestors} repeats
     * {@code X-Frame-Options} for browsers that prefer the newer spelling, and
     * {@code base-uri} and {@code form-action} close the two things an injected
     * fragment would reach for.
     *
     * <p><strong>This is a different surface from the one nginx covers.</strong>
     * The frontend image already sends a full policy, and it is a good one — but
     * from its {@code location /} block, which is the document. An API response
     * comes from {@code location /api/}, inherits none of it, and was measured
     * against the running deployment carrying no policy at all, through the proxy
     * and on the published port alike. The two do not overlap and neither
     * replaces the other.
     *
     * <p>The referrer policy differs from nginx's {@code strict-origin-when-cross-origin}
     * on purpose rather than by accident. That one governs navigation from a
     * page; this one is attached to responses that are never a page, where the
     * strictest answer costs nothing.
     *
     * <p>Deliberately absent: {@code Strict-Transport-Security}, because this
     * deployment terminates HTTP and an instruction never to use HTTP again would
     * make it unreachable while promising a protection it does not have; and
     * {@code Permissions-Policy}, because a JSON response has no document to deny
     * a camera to. Both would look like security and be neither.
     */
    static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // No cookies are used for authentication, so there is no CSRF surface.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/auth/signup", "/api/v1/auth/login").permitAll()
                        // Read-only discovery endpoints serve anonymous callers; the
                        // service layer still hides private repositories.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/repositories/**").permitAll()
                        // Polled by the container runtime, which holds no
                        // credentials. It reports only whether the database and
                        // storage answered.
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHENTICATED", "Authentication required", request.getRequestURI()))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "Access denied", request.getRequestURI())))
                .headers(headers -> headers
                        // Spring Security already sends nosniff, DENY, no-store
                        // and X-XSS-Protection: 0 - checked against a running
                        // instance rather than assumed. These are the three it
                        // does not send that are worth sending.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers
                                        .ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** Writes the same {@link ApiError} shape the controllers use, so clients see one format. */
    private void writeError(HttpServletResponse response, int status, String code, String message, String path)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiError.of(status, code, message, path));
    }
}
