package com.gitforge.issue;

import com.gitforge.repo.Repo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueRepository extends JpaRepository<Issue, UUID> {

    List<Issue> findByRepoOrderByNumberDesc(Repo repo);

    List<Issue> findByRepoAndStatusOrderByNumberDesc(Repo repo, IssueStatus status);

    Optional<Issue> findByRepoAndNumber(Repo repo, int number);

    long countByRepoAndStatus(Repo repo, IssueStatus status);

    @Query("SELECT COALESCE(MAX(i.number), 0) FROM Issue i WHERE i.repo = :repo")
    int findHighestNumber(@Param("repo") Repo repo);
}
