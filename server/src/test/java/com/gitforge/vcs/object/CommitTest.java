package com.gitforge.vcs.object;

import com.gitforge.vcs.GoldenVectors;
import com.gitforge.vcs.InMemoryObjectStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommitTest {

    private static final ObjectId TREE = ObjectId.fromHex(GoldenVectors.TREE_ROOT);
    private static final ObjectId OTHER_TREE = ObjectId.fromHex(GoldenVectors.TREE_SRC);

    private static final Signature ADA = new Signature(
            GoldenVectors.SIGNATURE_NAME,
            GoldenVectors.SIGNATURE_EMAIL,
            Instant.ofEpochSecond(GoldenVectors.SIGNATURE_EPOCH_SECONDS),
            ZoneOffset.UTC);

    private static Commit commit(List<ObjectId> parents, String message) {
        return Commit.of(TREE, parents, ADA, message);
    }

    @Nested
    @DisplayName("identity matches an independent implementation")
    class GoldenIdentity {

        @Test
        void initialCommitHasNoParents() {
            Commit initial = commit(List.of(), "Initial commit");

            assertThat(initial.id().toHex()).isEqualTo(GoldenVectors.COMMIT_INITIAL);
            assertThat(initial.isInitial()).isTrue();
            assertThat(initial.isMerge()).isFalse();
            assertThat(initial.parents()).isEmpty();
        }

        @Test
        void ordinaryCommitHasOneParent() {
            Commit second = commit(List.of(ObjectId.fromHex(GoldenVectors.COMMIT_INITIAL)), "Second commit");

            assertThat(second.id().toHex()).isEqualTo(GoldenVectors.COMMIT_SECOND);
            assertThat(second.isInitial()).isFalse();
            assertThat(second.isMerge()).isFalse();
        }

        @Test
        void mergeCommitHasTwoParents() {
            Commit merge = commit(
                    List.of(ObjectId.fromHex(GoldenVectors.COMMIT_SECOND),
                            ObjectId.fromHex(GoldenVectors.COMMIT_BRANCH)),
                    "Merge branch");

            assertThat(merge.id().toHex()).isEqualTo(GoldenVectors.COMMIT_MERGE);
            assertThat(merge.isMerge()).isTrue();
            assertThat(merge.parents()).hasSize(2);
        }

        @Test
        void serializedFormIsTheDocumentedLayout() {
            Commit initial = commit(List.of(), "Initial commit");

            assertThat(new String(initial.payload(), StandardCharsets.UTF_8)).isEqualTo("""
                    tree d760777a57381fefbb8cf06e181830d74ed2fae2
                    author Ada Lovelace <ada@example.com> 1700000000 +0000
                    committer Ada Lovelace <ada@example.com> 1700000000 +0000

                    Initial commit
                    """);
        }
    }

    @Nested
    @DisplayName("identity changes when any meaningful field changes")
    class IdentitySensitivity {

        private final ObjectId parent = ObjectId.fromHex(GoldenVectors.COMMIT_INITIAL);
        private final Commit baseline = commit(List.of(parent), "Second commit");

        @Test
        void changingTheTreeChangesTheId() {
            Commit altered = Commit.of(OTHER_TREE, List.of(parent), ADA, "Second commit");

            assertThat(altered.id()).isNotEqualTo(baseline.id());
        }

        @Test
        void changingTheParentChangesTheId() {
            Commit altered = commit(List.of(ObjectId.fromHex(GoldenVectors.COMMIT_BRANCH)), "Second commit");

            assertThat(altered.id()).isNotEqualTo(baseline.id());
        }

        @Test
        void removingTheParentChangesTheId() {
            assertThat(commit(List.of(), "Second commit").id()).isNotEqualTo(baseline.id());
        }

        @Test
        void changingTheMessageChangesTheId() {
            assertThat(commit(List.of(parent), "A different message").id()).isNotEqualTo(baseline.id());
        }

        @Test
        void changingTheAuthorNameChangesTheId() {
            Signature other = new Signature("Grace Hopper", GoldenVectors.SIGNATURE_EMAIL,
                    ADA.timestamp(), ADA.offset());

            assertThat(Commit.of(TREE, List.of(parent), other, "Second commit").id())
                    .isNotEqualTo(baseline.id());
        }

        @Test
        void changingTheAuthorEmailChangesTheId() {
            Signature other = new Signature(GoldenVectors.SIGNATURE_NAME, "grace@example.com",
                    ADA.timestamp(), ADA.offset());

            assertThat(Commit.of(TREE, List.of(parent), other, "Second commit").id())
                    .isNotEqualTo(baseline.id());
        }

        @Test
        void changingTheTimestampChangesTheId() {
            Signature later = new Signature(GoldenVectors.SIGNATURE_NAME, GoldenVectors.SIGNATURE_EMAIL,
                    ADA.timestamp().plusSeconds(1), ADA.offset());

            assertThat(Commit.of(TREE, List.of(parent), later, "Second commit").id())
                    .isNotEqualTo(baseline.id());
        }

        @Test
        void changingOnlyTheZoneOffsetChangesTheId() {
            // Same instant, different recorded offset: a different commit.
            Signature shifted = new Signature(GoldenVectors.SIGNATURE_NAME, GoldenVectors.SIGNATURE_EMAIL,
                    ADA.timestamp(), ZoneOffset.ofHoursMinutes(5, 30));

            assertThat(Commit.of(TREE, List.of(), shifted, "Initial commit").id().toHex())
                    .isEqualTo(GoldenVectors.COMMIT_OFFSET_0530)
                    .isNotEqualTo(GoldenVectors.COMMIT_INITIAL);
        }

        @Test
        void changingOnlyTheCommitterChangesTheId() {
            Signature other = new Signature("Grace Hopper", "grace@example.com",
                    ADA.timestamp(), ADA.offset());

            assertThat(new Commit(TREE, List.of(parent), ADA, other, "Second commit").id())
                    .isNotEqualTo(baseline.id());
        }

        @Test
        void swappingMergeParentsChangesTheId() {
            // Parent order carries meaning — parent 0 is the branch merged into —
            // so it is part of identity rather than an incidental ordering.
            ObjectId second = ObjectId.fromHex(GoldenVectors.COMMIT_SECOND);
            ObjectId branch = ObjectId.fromHex(GoldenVectors.COMMIT_BRANCH);

            assertThat(commit(List.of(second, branch), "Merge branch").id().toHex())
                    .isEqualTo(GoldenVectors.COMMIT_MERGE);
            assertThat(commit(List.of(branch, second), "Merge branch").id().toHex())
                    .isEqualTo(GoldenVectors.COMMIT_MERGE_PARENTS_SWAPPED)
                    .isNotEqualTo(GoldenVectors.COMMIT_MERGE);
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        void identicalMetadataProducesIdenticalIds() {
            assertThat(commit(List.of(), "Initial commit").id())
                    .isEqualTo(commit(List.of(), "Initial commit").id());
        }

        @Test
        void repeatedSerializationProducesIdenticalBytes() {
            Commit subject = commit(List.of(), "Initial commit");

            assertThat(subject.payload()).isEqualTo(subject.payload());
        }

        @Test
        void parentOrderIsPreservedNotSorted() {
            // Sorting would reorder these, since COMMIT_BRANCH sorts first.
            ObjectId second = ObjectId.fromHex(GoldenVectors.COMMIT_SECOND);
            ObjectId branch = ObjectId.fromHex(GoldenVectors.COMMIT_BRANCH);

            assertThat(branch).isLessThan(second);
            assertThat(commit(List.of(second, branch), "Merge branch").parents())
                    .containsExactly(second, branch);
        }
    }

    @Nested
    @DisplayName("message handling")
    class Messages {

        @Test
        void aTrailingNewlineIsAddedWhenAbsent() {
            assertThat(commit(List.of(), "No newline").message()).isEqualTo("No newline\n");
        }

        @Test
        void anExistingTrailingNewlineIsNotDuplicated() {
            assertThat(commit(List.of(), "Has newline\n").message()).isEqualTo("Has newline\n");
            assertThat(commit(List.of(), "Has newline\n").id())
                    .isEqualTo(commit(List.of(), "Has newline").id());
        }

        @Test
        void deliberateTrailingBlankLinesArePreserved() {
            assertThat(commit(List.of(), "Body\n\n").message()).isEqualTo("Body\n\n");
        }

        @Test
        void multiLineMessagesSurvive() {
            Commit subject = commit(List.of(), "Subject line\n\nA longer body explaining why.\n");

            assertThat(subject.message()).isEqualTo("Subject line\n\nA longer body explaining why.\n");
        }

        @Test
        void unicodeMessagesAreEncodedAsUtf8() {
            Commit subject = commit(List.of(), "Résumé: 変更\n");

            assertThat(ObjectFormat.parse(ObjectFormat.serialize(subject)).payload())
                    .isEqualTo(subject.payload());
            assertThat(((Commit) ObjectFormat.parse(ObjectFormat.serialize(subject))).message())
                    .isEqualTo("Résumé: 変更\n");
        }
    }

    @Nested
    @DisplayName("round trip and persistence")
    class RoundTrip {

        @Test
        void parsesBackToAnEqualCommit() {
            Commit original = commit(
                    List.of(ObjectId.fromHex(GoldenVectors.COMMIT_SECOND),
                            ObjectId.fromHex(GoldenVectors.COMMIT_BRANCH)),
                    "Merge branch");

            VcsObject parsed = ObjectFormat.parse(ObjectFormat.serialize(original));

            assertThat(parsed).isInstanceOf(Commit.class);
            Commit read = (Commit) parsed;
            assertThat(read.id()).isEqualTo(original.id());
            assertThat(read.tree()).isEqualTo(original.tree());
            assertThat(read.parents()).isEqualTo(original.parents());
            assertThat(read.author()).isEqualTo(original.author());
            assertThat(read.committer()).isEqualTo(original.committer());
            assertThat(read.message()).isEqualTo(original.message());
        }

        @Test
        void storesAndReadsBackThroughTheObjectStore() {
            InMemoryObjectStore store = new InMemoryObjectStore();
            Commit original = commit(List.of(), "Initial commit");

            ObjectId id = store.write(original);

            // The id is the content hash, never a generated identifier.
            assertThat(id.toHex()).isEqualTo(GoldenVectors.COMMIT_INITIAL);
            assertThat(store.readCommit(id)).isEqualTo(original);
            assertThat(store.readCommit(id).message()).isEqualTo("Initial commit\n");
        }

        @Test
        void writingTheSameCommitTwiceStoresItOnce() {
            InMemoryObjectStore store = new InMemoryObjectStore();

            store.write(commit(List.of(), "Initial commit"));
            store.write(commit(List.of(), "Initial commit"));

            assertThat(store.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void rejectsAMissingTree() {
            assertThatThrownBy(() -> Commit.of(null, List.of(), ADA, "message"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("root tree");
        }

        @Test
        void rejectsNullParentsOrEntries() {
            assertThatThrownBy(() -> Commit.of(TREE, null, ADA, "message"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Commit.of(TREE, java.util.Arrays.asList((ObjectId) null), ADA, "message"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsAMissingAuthorOrMessage() {
            assertThatThrownBy(() -> Commit.of(TREE, List.of(), null, "message"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Commit.of(TREE, List.of(), ADA, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsMalformedCommitPayloads() {
            assertThatThrownBy(() -> ObjectFormat.parse(
                    ObjectFormat.frame(ObjectType.COMMIT, "no blank line".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("blank line");

            assertThatThrownBy(() -> ObjectFormat.parse(ObjectFormat.frame(
                    ObjectType.COMMIT, "parent abc\n\nmessage\n".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(CorruptObjectException.class);

            assertThatThrownBy(() -> ObjectFormat.parse(ObjectFormat.frame(
                    ObjectType.COMMIT, "banana yes\n\nmessage\n".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("unrecognised header");
        }

        @Test
        void parentsAreUnmodifiable() {
            Commit subject = commit(List.of(ObjectId.fromHex(GoldenVectors.COMMIT_INITIAL)), "Second commit");

            assertThatThrownBy(() -> subject.parents().add(TREE))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
