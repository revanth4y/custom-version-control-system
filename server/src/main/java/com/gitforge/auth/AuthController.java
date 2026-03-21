package com.gitforge.auth;

import com.gitforge.auth.dto.AuthResponse;
import com.gitforge.auth.dto.LoginRequest;
import com.gitforge.auth.dto.SignupRequest;
import com.gitforge.common.error.ApiException;
import com.gitforge.common.error.TooManyRequestsException;
import com.gitforge.security.AuthAttemptLimiter;
import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthAttemptLimiter limiter;

    public AuthController(AuthService authService, AuthAttemptLimiter limiter) {
        this.authService = authService;
        this.limiter = limiter;
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(
            @Valid @RequestBody SignupRequest request, HttpServletRequest http) {

        AuthResponse created = throttled(http, () -> authService.signup(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return throttled(http, () -> authService.login(request));
    }

    /**
     * Runs an authentication attempt under the rate limiter.
     *
     * <p>The caller's address is taken from the connection rather than from
     * {@code X-Forwarded-For}, which any client can set to whatever it likes -
     * trusting it would let an attacker have a fresh allowance per request.
     * Behind a reverse proxy the deployment must set
     * {@code server.forward-headers-strategy} so the connection reports the real
     * client; otherwise every caller shares one bucket.
     */
    private AuthResponse throttled(HttpServletRequest http, Supplier<AuthResponse> attempt) {
        String address = http.getRemoteAddr();

        Duration wait = limiter.retryAfter(address);
        if (!wait.isZero()) {
            // Deliberately says nothing about the credentials or the account.
            throw new TooManyRequestsException(
                    "Too many authentication attempts. Try again later.", wait);
        }

        try {
            AuthResponse response = attempt.get();
            limiter.recordSuccess(address);
            return response;
        } catch (ApiException | AuthenticationException ex) {
            // Both, and only these two. A rejected password arrives as Spring
            // Security's AuthenticationException while a taken username arrives
            // as ours; counting one without the other would leave the endpoint
            // that actually gets guessed unprotected. A server fault is not
            // counted - an outage should not lock people out on top of it.
            limiter.recordFailure(address);
            throw ex;
        }
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return UserResponse.from(principal.user());
    }
}
