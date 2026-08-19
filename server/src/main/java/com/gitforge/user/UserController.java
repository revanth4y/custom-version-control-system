package com.gitforge.user;

import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.dto.UpdateUserRequest;
import com.gitforge.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{username}")
    public UserResponse getProfile(@PathVariable String username) {
        return userService.getProfile(username);
    }

    @PatchMapping("/me")
    public UserResponse updateOwnProfile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateOwnProfile(principal.id(), request);
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteOwnAccount(@AuthenticationPrincipal AuthenticatedUser principal) {
        userService.deleteOwnAccount(principal.id());
        return ResponseEntity.noContent().build();
    }
}
