package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.repository.RepositoryStatistics;

import java.time.LocalDate;
import java.util.List;

/**
 * Aggregate facts about a repository.
 *
 * @param storedObjects blobs, trees and commits held for this repository; a
 *     direct window onto the content-addressed store
 */
public record InsightsResponse(
        int commits,
        int branches,
        int files,
        long storedObjects,
        List<Contributor> contributors,
        List<Activity> activity) {

    public record Contributor(String name, String email, int commits) {
    }

    public record Activity(LocalDate date, int count) {
    }

    public static InsightsResponse from(RepositoryStatistics.Stats stats) {
        return new InsightsResponse(
                stats.commits(),
                stats.branches(),
                stats.files(),
                stats.storedObjects(),
                stats.contributors().stream()
                        .map(c -> new Contributor(c.name(), c.email(), c.commits()))
                        .toList(),
                stats.activity().stream()
                        .map(a -> new Activity(a.date(), a.count()))
                        .toList());
    }
}
