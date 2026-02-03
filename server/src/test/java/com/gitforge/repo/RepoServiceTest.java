package com.gitforge.repo;

import com.gitforge.TestFixtures;
import com.gitforge.common.error.ConflictException;
import com.gitforge.common.error.ForbiddenException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.repo.dto.CreateRepoRequest;
import com.gitforge.repo.dto.UpdateRepoRequest;
import com.gitforge.user.User;
import com.gitforge.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authorization rules for repositories, isolated from the database.
 *
 * <p>These are the checks the previous implementation lacked entirely: every
 * mutating endpoint was reachable by any caller.
 */
@ExtendWith(MockitoExtension.class)
class RepoServiceTest {

    @Mock
    private RepoRepository repoRepository;

    @Mock
    private UserService userService;

    @InjectMocks
    private RepoService repoService;

    private User owner;
    private User stranger;

    @BeforeEach
    void setUp() {
        owner = TestFixtures.user("owner");
        stranger = TestFixtures.user("stranger");
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        void savesRepositoryWhenNameIsFree() {
            var request = new CreateRepoRequest("portfolio", "My site", RepoVisibility.PUBLIC);
            when(repoRepository.existsByOwnerAndNameIgnoreCase(owner, "portfolio")).thenReturn(false);
            when(repoRepository.save(any(Repo.class)))
                    .thenAnswer(invocation -> TestFixtures.repo(owner, "portfolio", RepoVisibility.PUBLIC));

            var response = repoService.create(owner, request);

            assertThat(response.name()).isEqualTo("portfolio");
            assertThat(response.ownerUsername()).isEqualTo("owner");
        }

        @Test
        void rejectsDuplicateNameForSameOwner() {
            var request = new CreateRepoRequest("portfolio", null, RepoVisibility.PUBLIC);
            when(repoRepository.existsByOwnerAndNameIgnoreCase(owner, "portfolio")).thenReturn(true);

            assertThatThrownBy(() -> repoService.create(owner, request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already own a repository named");

            verify(repoRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("read access")
    class ReadAccess {

        @Test
        void anonymousViewerCanReadPublicRepository() {
            Repo repo = TestFixtures.repo(owner, "public-repo", RepoVisibility.PUBLIC);
            when(repoRepository.findByOwnerUsernameAndName("owner", "public-repo")).thenReturn(Optional.of(repo));

            assertThat(repoService.requireReadable("owner", "public-repo", null)).isSameAs(repo);
        }

        @Test
        void privateRepositoryIsReportedAsMissingToStrangers() {
            Repo repo = TestFixtures.repo(owner, "secret", RepoVisibility.PRIVATE);
            when(repoRepository.findByOwnerUsernameAndName("owner", "secret")).thenReturn(Optional.of(repo));

            // Not Forbidden: a 403 would confirm the repository exists.
            assertThatThrownBy(() -> repoService.requireReadable("owner", "secret", stranger))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Repository not found");
        }

        @Test
        void ownerCanReadOwnPrivateRepository() {
            Repo repo = TestFixtures.repo(owner, "secret", RepoVisibility.PRIVATE);
            when(repoRepository.findByOwnerUsernameAndName("owner", "secret")).thenReturn(Optional.of(repo));

            assertThat(repoService.requireReadable("owner", "secret", owner)).isSameAs(repo);
        }
    }

    @Nested
    @DisplayName("write access")
    class WriteAccess {

        @Test
        void ownerMayModifyOwnRepository() {
            Repo repo = TestFixtures.repo(owner, "portfolio", RepoVisibility.PUBLIC);
            when(repoRepository.findById(repo.getId())).thenReturn(Optional.of(repo));

            assertThat(repoService.requireWritable(repo.getId(), owner)).isSameAs(repo);
        }

        @Test
        void strangerMayNotModifyPublicRepository() {
            Repo repo = TestFixtures.repo(owner, "portfolio", RepoVisibility.PUBLIC);
            when(repoRepository.findById(repo.getId())).thenReturn(Optional.of(repo));

            assertThatThrownBy(() -> repoService.requireWritable(repo.getId(), stranger))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void strangerMayNotLearnThatPrivateRepositoryExists() {
            Repo repo = TestFixtures.repo(owner, "secret", RepoVisibility.PRIVATE);
            when(repoRepository.findById(repo.getId())).thenReturn(Optional.of(repo));

            assertThatThrownBy(() -> repoService.requireWritable(repo.getId(), stranger))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void anonymousCallerMayNotModifyAnything() {
            Repo repo = TestFixtures.repo(owner, "portfolio", RepoVisibility.PUBLIC);
            when(repoRepository.findById(repo.getId())).thenReturn(Optional.of(repo));

            assertThatThrownBy(() -> repoService.requireWritable(repo.getId(), null))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void deletingRepositoryOwnedByAnotherUserIsRejected() {
            Repo repo = TestFixtures.repo(owner, "portfolio", RepoVisibility.PUBLIC);
            when(repoRepository.findById(repo.getId())).thenReturn(Optional.of(repo));

            assertThatThrownBy(() -> repoService.delete(repo.getId(), stranger))
                    .isInstanceOf(ForbiddenException.class);

            verify(repoRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        void renamingOntoAnExistingNameIsRejected() {
            Repo repo = TestFixtures.repo(owner, "portfolio", RepoVisibility.PUBLIC);
            when(repoRepository.findById(repo.getId())).thenReturn(Optional.of(repo));
            when(repoRepository.existsByOwnerAndNameIgnoreCase(owner, "taken")).thenReturn(true);

            var request = new UpdateRepoRequest("taken", null, null);

            assertThatThrownBy(() -> repoService.update(repo.getId(), owner, request))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void nullFieldsLeaveExistingValuesUnchanged() {
            Repo repo = TestFixtures.repo(owner, "portfolio", RepoVisibility.PUBLIC);
            when(repoRepository.findById(repo.getId())).thenReturn(Optional.of(repo));

            var response = repoService.update(repo.getId(), owner, new UpdateRepoRequest(null, "Updated", null));

            assertThat(response.name()).isEqualTo("portfolio");
            assertThat(response.description()).isEqualTo("Updated");
            assertThat(response.visibility()).isEqualTo(RepoVisibility.PUBLIC);
        }
    }

    @Nested
    @DisplayName("listing by owner")
    class ListByOwner {

        @Test
        void ownerSeesPrivateRepositoriesToo() {
            when(userService.requireByUsername("owner")).thenReturn(owner);
            when(repoRepository.findByOwnerOrderByUpdatedAtDesc(owner)).thenReturn(List.of(
                    TestFixtures.repo(owner, "public-repo", RepoVisibility.PUBLIC),
                    TestFixtures.repo(owner, "secret", RepoVisibility.PRIVATE)));

            assertThat(repoService.listByOwner("owner", owner)).hasSize(2);
        }

        @Test
        void strangerSeesOnlyPublicRepositories() {
            when(userService.requireByUsername("owner")).thenReturn(owner);
            when(repoRepository.findByOwnerAndVisibilityOrderByUpdatedAtDesc(owner, RepoVisibility.PUBLIC))
                    .thenReturn(List.of(TestFixtures.repo(owner, "public-repo", RepoVisibility.PUBLIC)));

            assertThat(repoService.listByOwner("owner", stranger))
                    .extracting(r -> r.name())
                    .containsExactly("public-repo");
        }
    }
}
