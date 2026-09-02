package com.gitforge.release;

import com.gitforge.repo.Repo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReleaseRepository extends JpaRepository<Release, UUID> {

    List<Release> findByRepoOrderByCreatedAtDesc(Repo repo);

    Optional<Release> findByRepoAndTagName(Repo repo, String tagName);

    boolean existsByRepoAndTagName(Repo repo, String tagName);
}
