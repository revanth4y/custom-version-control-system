package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import com.gitforge.vcs.repository.RepositoryId;
import com.gitforge.vcs.repository.VcsRepositoryFactory;
import com.gitforge.vcs.storage.ObjectStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What repository handle reuse looks like from the request path.
 *
 * <p>Every VCS request opens a repository, and opening used to build a new
 * object store, which meant a new and empty verified-object cache each time: a
 * commit read on one request was read from disk, inflated and hashed again on
 * the next. The store now survives between opens, so this checks the thing that
 * actually changed - the same store reached twice - and then the lifecycle
 * around it, which is where reuse could go wrong rather than merely be slow.
 *
 * <p>The factory is a singleton bean and outlives every test in the suite, while
 * {@code AbstractIntegrationTest} empties the database and the storage directory
 * before each one. That combination is exactly the case worth pinning: a store
 * kept from a previous test must never answer for a repository created later.
 */
class RepositoryHandleReuseIT extends AbstractIntegrationTest {

    @Autowired
    private VcsRepositoryFactory factory;

    private String createRepo(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"%s","description":"a repo","visibility":"PUBLIC"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asString();
    }

    private void commit(String token, String owner, String name, String content) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/" + owner + "/" + name + "/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"a commit","changes":[
                                  {"operation":"PUT","path":"file.txt","content":"%s"}]}
                                """.formatted(content)))
                .andExpect(status().isCreated());
    }

    private ObjectStore storeOf(String repoId) {
        return factory.open(RepositoryId.of(repoId)).objects();
    }

    @Test
    @DisplayName("two requests against one repository reach the same object store")
    void repeatedRequestsReuseTheStore() throws Exception {
        String token = registerAndLogin("octocat");
        String id = createRepo(token, "demo");
        commit(token, "octocat", "demo", "first");

        ObjectStore before = storeOf(id);
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")).andExpect(status().isOk());

        assertThat(storeOf(id))
                .as("the cache lives in the store, so the store has to outlive the request")
                .isSameAs(before);
    }

    @Test
    @DisplayName("repeated reads return exactly what the first one did")
    void repeatedReadsAreUnchanged() throws Exception {
        String token = registerAndLogin("octocat");
        createRepo(token, "demo");
        commit(token, "octocat", "demo", "first");
        commit(token, "octocat", "demo", "second");

        String first = mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (int i = 0; i < 5; i++) {
            assertThat(mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString())
                    .as("a warm store answers what a cold one did, byte for byte")
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName("two repositories never share a store, and never each other's objects")
    void repositoriesStayIsolated() throws Exception {
        String token = registerAndLogin("octocat");
        String one = createRepo(token, "one");
        String two = createRepo(token, "two");
        commit(token, "octocat", "one", "only in one");
        commit(token, "octocat", "two", "only in two");

        assertThat(storeOf(one)).isNotSameAs(storeOf(two));

        String first = mockMvc.perform(get("/api/v1/repositories/octocat/one/commits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/v1/repositories/octocat/two/commits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(first)
                .as("each repository answers with its own commit, not the other's")
                .isNotEqualTo(second);
    }

    @Test
    @DisplayName("a written object is visible to the very next request")
    void writesAreVisibleImmediately() throws Exception {
        String token = registerAndLogin("octocat");
        createRepo(token, "demo");
        commit(token, "octocat", "demo", "first");

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        commit(token, "octocat", "demo", "second");

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("deleting a repository leaves nothing that can answer for it")
    void deletionIsComplete() throws Exception {
        String token = registerAndLogin("octocat");
        String id = createRepo(token, "demo");
        commit(token, "octocat", "demo", "first");
        storeOf(id);

        mockMvc.perform(delete("/api/v1/repositories/" + id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/repositories/octocat/demo"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a repository created after storage was cleared behaves as a new one")
    void survivesTheSuiteResettingStorage() throws Exception {
        // resetState() in the base class empties the database and the storage
        // directory before every test while the factory bean lives on. A
        // repository created now must not see anything an earlier test left in
        // a cached store.
        String token = registerAndLogin("octocat");
        String id = createRepo(token, "fresh");

        assertThat(factory.open(RepositoryId.of(id)).objects().count())
                .as("a repository created after a reset starts empty")
                .isZero();

        commit(token, "octocat", "fresh", "first");

        mockMvc.perform(get("/api/v1/repositories/octocat/fresh/commits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        assertThat(factory.open(RepositoryId.of(id)).objects().count()).isPositive();
    }
}
