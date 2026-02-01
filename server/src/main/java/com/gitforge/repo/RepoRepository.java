package com.gitforge.repo;

import com.gitforge.user.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepoRepository extends JpaRepository<Repo, UUID> {

    @Query("""
            SELECT r FROM Repo r
            JOIN FETCH r.owner o
            WHERE LOWER(o.username) = LOWER(:username) AND LOWER(r.name) = LOWER(:name)
            """)
    Optional<Repo> findByOwnerUsernameAndName(@Param("username") String username, @Param("name") String name);

    boolean existsByOwnerAndNameIgnoreCase(User owner, String name);

    List<Repo> findByOwnerOrderByUpdatedAtDesc(User owner);

    List<Repo> findByOwnerAndVisibilityOrderByUpdatedAtDesc(User owner, RepoVisibility visibility);

    Page<Repo> findByVisibility(RepoVisibility visibility, Pageable pageable);

    /**
     * Locks the repository row so that per-repository issue numbering is
     * serialised across concurrent transactions.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Repo r WHERE r.id = :id")
    Optional<Repo> findByIdForUpdate(@Param("id") UUID id);
}
