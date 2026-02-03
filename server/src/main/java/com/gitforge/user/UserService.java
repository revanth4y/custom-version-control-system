package com.gitforge.user;

import com.gitforge.common.error.ConflictException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.user.dto.UpdateUserRequest;
import com.gitforge.user.dto.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Profile reads and self-service account updates. */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public User requireByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(String username) {
        return UserResponse.from(requireByUsername(username));
    }

    /**
     * Updates the caller's own account. The caller's identity comes from the
     * security context, never from a path variable, so one user cannot target
     * another's account.
     */
    @Transactional
    public UserResponse updateOwnProfile(UUID userId, UpdateUserRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        if (request.email() != null && !request.email().equalsIgnoreCase(user.getEmail())) {
            if (userRepository.existsByEmailIgnoreCase(request.email())) {
                throw new ConflictException("Email is already registered");
            }
            user.setEmail(request.email());
        }
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.bio() != null) {
            user.setBio(request.bio());
        }
        if (request.password() != null) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }

        return UserResponse.from(user);
    }

    @Transactional
    public void deleteOwnAccount(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("User not found");
        }
        // Repositories cascade; issues authored elsewhere survive with a null author.
        userRepository.deleteById(userId);
    }
}
