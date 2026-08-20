package com.gitforge.vcsapi;

import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.ContributionsResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Daily commit activity for one person. */
@RestController
@RequestMapping("/api/v1/users/{username}")
public class ContributionController {

    private final ContributionApiService contributions;

    public ContributionController(ContributionApiService contributions) {
        this.contributions = contributions;
    }

    @GetMapping("/contributions")
    public ContributionsResponse contributions(
            @PathVariable String username,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return contributions.contributions(username, viewerOf(principal), from, to);
    }

    private static User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
