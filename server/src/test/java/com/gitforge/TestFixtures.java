package com.gitforge;

import com.gitforge.repo.Repo;
import com.gitforge.repo.RepoVisibility;
import com.gitforge.user.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

/**
 * Builders for detached entities in unit tests.
 *
 * <p>Identifiers are normally assigned by the persistence provider, so tests that
 * never touch a database set them explicitly here.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static User user(String username) {
        User user = new User(username, username + "@example.com", "irrelevant-hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    public static Repo repo(User owner, String name, RepoVisibility visibility) {
        Repo repo = new Repo(owner, name, "description", visibility);
        ReflectionTestUtils.setField(repo, "id", UUID.randomUUID());
        return repo;
    }
}
