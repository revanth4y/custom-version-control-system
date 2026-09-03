package com.gitforge.vcs.ref;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a tag may be called.
 *
 * <p>The rejection cases are the point. A tag name becomes a path under {@code
 * refs/tags}, so an unvalidated name is a filesystem write primitive — the same
 * hazard branch names carry, and tested to the same standard here rather than
 * assumed to be covered because the rules resemble each other.
 */
class TagNameTest {

    @Nested
    @DisplayName("names that are accepted")
    class Accepted {

        @ParameterizedTest
        @ValueSource(strings = {
                "v1",
                "v1.0.0",
                "v2.0.14",
                "release-candidate",
                "release/v1.0",
                "release/2026/final",
                "a",
                "UPPERCASE",
                "with_underscores",
                "1.0",
                "v1.0.0-rc.1+build.5"
        })
        void areReturnedUnchanged(String name) {
            assertThat(TagName.validate(name)).isEqualTo(name);
        }

        @Test
        void slashesNestAsHierarchyJustAsBranchesDo() {
            assertThat(TagName.validate("release/v1.0")).isEqualTo("release/v1.0");
        }

        @Test
        void aNameOfExactlyTheMaximumLengthIsAllowed() {
            String name = "v".repeat(255);

            assertThat(TagName.validate(name)).hasSize(255);
        }

        @Test
        void hexOfAnyLengthOtherThanAFullObjectIdIsFine() {
            // 39 and 41 characters: neither can be mistaken for an object id.
            assertThat(TagName.validate("a".repeat(39))).hasSize(39);
            assertThat(TagName.validate("a".repeat(41))).hasSize(41);
        }
    }

    @Nested
    @DisplayName("empty and reserved")
    class EmptyAndReserved {

        @Test
        void nullIsRejected() {
            assertThatThrownBy(() -> TagName.validate(null))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("must not be empty");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t", "   "})
        void blankIsRejected(String name) {
            assertThatThrownBy(() -> TagName.validate(name))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        void headIsReserved() {
            assertThatThrownBy(() -> TagName.validate("HEAD"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("reserved");
        }

        @Test
        void aNameLongerThanTheMaximumIsRejected() {
            assertThatThrownBy(() -> TagName.validate("v".repeat(256)))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("at most 255");
        }
    }

    @Nested
    @DisplayName("path traversal and unsafe paths")
    class PathSafety {

        @ParameterizedTest
        @ValueSource(strings = {
                "..",
                "../escape",
                "../../objects/ab/cdef",
                "release/../../../etc/passwd",
                "a/../../b",
                "."
        })
        void relativeSegmentsAreRejected(String name) {
            assertThatThrownBy(() -> TagName.validate(name))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("'.' or '..'");
        }

        @Test
        void anAbsoluteWindowsPathIsRejected() {
            assertThatThrownBy(() -> TagName.validate("C:\\work"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("absolute path");
        }

        @ParameterizedTest
        @ValueSource(strings = {"/leading", "trailing/", "/"})
        void leadingOrTrailingSlashIsRejected(String name) {
            assertThatThrownBy(() -> TagName.validate(name))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("'/'");
        }

        @Test
        void anEmptySegmentIsRejected() {
            assertThatThrownBy(() -> TagName.validate("release//v1"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("empty segment");
        }

        @Test
        void aBackslashIsRejectedSoAWindowsSeparatorCannotNest() {
            assertThatThrownBy(() -> TagName.validate("release\\v1"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("must not contain");
        }
    }

    @Nested
    @DisplayName("characters that break refs or revision syntax")
    class Characters {

        @ParameterizedTest
        @ValueSource(strings = {
                "v1~1", "v1^2", "v1:x", "v1?", "v1*", "v1[a]", "v1\"x\"",
                "v1'x'", "v1<x", "v1>x", "v1|x", "v 1"
        })
        void forbiddenCharactersAreRejected(String name) {
            assertThatThrownBy(() -> TagName.validate(name))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("must not contain");
        }

        @Test
        void controlCharactersAreRejected() {
            assertThatThrownBy(() -> TagName.validate("v1\u0001"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("control characters");

            assertThatThrownBy(() -> TagName.validate("v1\u007F"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("control characters");
        }

        @Test
        void theReflogSyntaxIsRejected() {
            assertThatThrownBy(() -> TagName.validate("v1@{0}"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("@{");
        }
    }

    @Nested
    @DisplayName("segment rules")
    class Segments {

        @Test
        void aSegmentMayNotStartWithADot() {
            assertThatThrownBy(() -> TagName.validate("release/.hidden"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("must not start with '.'");
        }

        @Test
        void aSegmentMayNotStartWithADash() {
            assertThatThrownBy(() -> TagName.validate("release/-v1"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("must not start with '-'");
        }

        @Test
        void aSegmentMayNotEndWithTheLockSuffix() {
            assertThatThrownBy(() -> TagName.validate("v1.lock"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining(".lock");
        }
    }

    @Nested
    @DisplayName("a tag may not be named as an object id")
    class ObjectIdShape {

        @Test
        void aFullHexObjectIdIsRejected() {
            assertThatThrownBy(() -> TagName.validate("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("must not be an object id");
        }

        @Test
        void uppercaseHexIsRejectedToo() {
            assertThatThrownBy(() -> TagName.validate("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))
                    .isInstanceOf(RefException.class)
                    .hasMessageContaining("must not be an object id");
        }

        @Test
        void fortyCharactersThatAreNotHexAreFine() {
            // Same length, but 'z' is not a hex digit, so nothing is shadowed.
            String name = "z".repeat(40);

            assertThat(TagName.validate(name)).isEqualTo(name);
        }
    }
}
