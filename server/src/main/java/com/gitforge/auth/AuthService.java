package com.gitforge.auth;

import com.gitforge.auth.dto.AuthResponse;
import com.gitforge.auth.dto.LoginRequest;
import com.gitforge.auth.dto.SignupRequest;
import com.gitforge.common.error.ConflictException;
import com.gitforge.security.JwtService;
import com.gitforge.user.User;
import com.gitforge.user.UserRepository;
import com.gitforge.user.dto.UserResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration and password-based sign-in. */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse signup(SignupRequest request) {
        if (userRepository.existsByUsernameIgnoreCase(request.username())) {
            throw new ConflictException("Username is already taken");
        }
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("Email is already registered");
        }

        User user = new User(
                request.username(),
                request.email(),
                passwordEncoder.encode(request.password()));

        return toAuthResponse(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .orElse(null);

        // Hash comparison runs even when no account matched, so response timing
        // does not reveal whether an email is registered.
        String storedHash = user != null ? user.getPasswordHash() : NON_MATCHING_HASH;
        boolean matches = passwordEncoder.matches(request.password(), storedHash);

        if (user == null || !matches) {
            throw new BadCredentialsException("Invalid email or password");
        }
        return toAuthResponse(user);
    }

    private AuthResponse toAuthResponse(User user) {
        return new AuthResponse(
                jwtService.issueToken(user.getId()),
                jwtService.getExpiry().toSeconds(),
                UserResponse.from(user));
    }

    /**
     * A well-formed BCrypt hash that no password matches, used to keep the
     * verification cost identical for unknown accounts.
     */
    private static final String NON_MATCHING_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
}
