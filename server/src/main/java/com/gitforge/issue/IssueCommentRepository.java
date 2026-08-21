package com.gitforge.issue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IssueCommentRepository extends JpaRepository<IssueComment, UUID> {

    List<IssueComment> findByIssueOrderByCreatedAtAsc(Issue issue);

    long countByIssue(Issue issue);
}
