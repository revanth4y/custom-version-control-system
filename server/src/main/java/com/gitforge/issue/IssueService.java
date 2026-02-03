package com.gitforge.issue;

import com.gitforge.common.error.ForbiddenException;
import com.gitforge.common.error.NotFoundException;
import com.gitforge.issue.dto.CreateIssueRequest;
import com.gitforge.issue.dto.IssueResponse;
import com.gitforge.issue.dto.UpdateIssueRequest;
import com.gitforge.repo.Repo;
import com.gitforge.repo.RepoRepository;
import com.gitforge.repo.RepoService;
import com.gitforge.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Issue lifecycle, scoped to a repository the caller may read. */
@Service
public class IssueService {

    private final IssueRepository issueRepository;
    private final RepoRepository repoRepository;
    private final RepoService repoService;

    public IssueService(IssueRepository issueRepository, RepoRepository repoRepository, RepoService repoService) {
        this.issueRepository = issueRepository;
        this.repoRepository = repoRepository;
        this.repoService = repoService;
    }

    /**
     * Files a new issue.
     *
     * <p>The owning repository row is locked for the duration of the transaction
     * so that two concurrent submissions cannot read the same highest number and
     * collide on the {@code (repo_id, number)} uniqueness constraint.
     */
    @Transactional
    public IssueResponse create(String ownerUsername, String repoName, User author, CreateIssueRequest request) {
        Repo readable = repoService.requireReadable(ownerUsername, repoName, author);

        Repo locked = repoRepository.findByIdForUpdate(readable.getId())
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        int nextNumber = issueRepository.findHighestNumber(locked) + 1;
        Issue issue = new Issue(locked, author, nextNumber, request.title(), request.body());

        return IssueResponse.from(issueRepository.save(issue));
    }

    @Transactional(readOnly = true)
    public List<IssueResponse> list(String ownerUsername, String repoName, User viewer, IssueStatus status) {
        Repo repo = repoService.requireReadable(ownerUsername, repoName, viewer);

        List<Issue> issues = status == null
                ? issueRepository.findByRepoOrderByNumberDesc(repo)
                : issueRepository.findByRepoAndStatusOrderByNumberDesc(repo, status);

        return issues.stream().map(IssueResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public IssueResponse get(String ownerUsername, String repoName, int number, User viewer) {
        Repo repo = repoService.requireReadable(ownerUsername, repoName, viewer);
        return IssueResponse.from(requireIssue(repo, number));
    }

    @Transactional
    public IssueResponse update(UUID issueId, User viewer, UpdateIssueRequest request) {
        Issue issue = requireEditable(issueId, viewer);

        if (request.title() != null) {
            issue.setTitle(request.title());
        }
        if (request.body() != null) {
            issue.setBody(request.body());
        }
        if (request.status() != null) {
            issue.setStatus(request.status());
        }

        return IssueResponse.from(issue);
    }

    @Transactional
    public void delete(UUID issueId, User viewer) {
        issueRepository.delete(requireEditable(issueId, viewer));
    }

    private Issue requireIssue(Repo repo, int number) {
        return issueRepository.findByRepoAndNumber(repo, number)
                .orElseThrow(() -> new NotFoundException("Issue not found"));
    }

    /** Loads an issue the viewer may edit: its author, or the repository owner. */
    private Issue requireEditable(UUID issueId, User viewer) {
        Issue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new NotFoundException("Issue not found"));

        if (!issue.getRepo().isReadableBy(viewer)) {
            throw new NotFoundException("Issue not found");
        }
        if (!issue.isEditableBy(viewer)) {
            throw new ForbiddenException("Only the issue author or the repository owner may modify it");
        }
        return issue;
    }
}
