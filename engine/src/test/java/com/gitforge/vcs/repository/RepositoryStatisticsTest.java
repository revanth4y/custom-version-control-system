package com.gitforge.vcs.repository;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryStatisticsTest {

    @TempDir
    Path storageRoot;

    private VcsRepository repository;
    private int sequence;

    @BeforeEach
    void setUp() {
        repository = new VcsRepositoryFactory(storageRoot).initialise(RepositoryId.of("demo"), "main");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private ObjectId commit(String branch, String author, String email, Instant when, FileChange... changes) {
        return repository.commits().commit(
                branch, List.of(changes), Signature.of(author, email, when), "commit " + sequence++);
    }

    private Instant day(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }

    @Test
    void afreshRepositoryReportsZeroes() {
        var stats = repository.statistics().compute();

        assertThat(stats.commits()).isZero();
        assertThat(stats.branches()).isZero();
        assertThat(stats.files()).isZero();
        assertThat(stats.contributors()).isEmpty();
        assertThat(stats.activity()).isEmpty();
    }

    @Test
    void countsCommitsBranchesAndFiles() {
        commit("main", "Ada", "ada@example.com", day("2026-08-01"),
                FileChange.put("a.txt", bytes("a\n")),
                FileChange.put("src/b.txt", bytes("b\n")));
        repository.branches().createBranchFrom("feature", "main");
        commit("feature", "Ada", "ada@example.com", day("2026-08-02"),
                FileChange.put("c.txt", bytes("c\n")));

        var stats = repository.statistics().compute();

        // Both branches are walked, so the feature commit counts too.
        assertThat(stats.commits()).isEqualTo(2);
        assertThat(stats.branches()).isEqualTo(2);
        // Files are those in the tree HEAD resolves to, which is still main.
        assertThat(stats.files()).isEqualTo(2);
        assertThat(stats.storedObjects()).isPositive();
    }

    @Test
    void countsACommitOnTwoBranchesOnlyOnce() {
        commit("main", "Ada", "ada@example.com", day("2026-08-01"), FileChange.put("a.txt", bytes("a\n")));
        repository.branches().createBranchFrom("copy", "main");

        assertThat(repository.statistics().compute().commits()).isEqualTo(1);
    }

    @Test
    void groupsContributorsByEmailMostActiveFirst() {
        commit("main", "Ada", "ada@example.com", day("2026-08-01"), FileChange.put("a.txt", bytes("a\n")));
        commit("main", "Ada", "ada@example.com", day("2026-08-02"), FileChange.put("b.txt", bytes("b\n")));
        commit("main", "Grace", "grace@example.com", day("2026-08-03"), FileChange.put("c.txt", bytes("c\n")));

        var contributors = repository.statistics().compute().contributors();

        assertThat(contributors).hasSize(2);
        assertThat(contributors.getFirst().email()).isEqualTo("ada@example.com");
        assertThat(contributors.getFirst().commits()).isEqualTo(2);
        assertThat(contributors.get(1).commits()).isEqualTo(1);
    }

    @Test
    void bucketsActivityByAuthorDate() {
        commit("main", "Ada", "ada@example.com", day("2026-08-01"), FileChange.put("a.txt", bytes("a\n")));
        commit("main", "Ada", "ada@example.com", day("2026-08-01"), FileChange.put("b.txt", bytes("b\n")));
        commit("main", "Ada", "ada@example.com", day("2026-08-03"), FileChange.put("c.txt", bytes("c\n")));

        var activity = repository.statistics().compute().activity();

        assertThat(activity).hasSize(2);
        assertThat(activity.getFirst().date()).isEqualTo(LocalDate.parse("2026-08-01"));
        assertThat(activity.getFirst().count()).isEqualTo(2);
        assertThat(activity.get(1).count()).isEqualTo(1);
    }

    @Test
    void activityIsOrderedOldestFirst() {
        commit("main", "Ada", "ada@example.com", day("2026-08-05"), FileChange.put("a.txt", bytes("a\n")));
        commit("main", "Ada", "ada@example.com", day("2026-08-01"), FileChange.put("b.txt", bytes("b\n")));

        assertThat(repository.statistics().compute().activity())
                .extracting(day -> day.date())
                .isSorted();
    }

    @Test
    void storedObjectCountMatchesTheObjectStore() {
        commit("main", "Ada", "ada@example.com", day("2026-08-01"), FileChange.put("a.txt", bytes("a\n")));

        assertThat(repository.statistics().compute().storedObjects())
                .isEqualTo(repository.objects().count());
    }
}
