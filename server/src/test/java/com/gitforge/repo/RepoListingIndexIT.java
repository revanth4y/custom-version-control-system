package com.gitforge.repo;

import com.gitforge.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The index behind the public repository listing.
 *
 * <p>GET /api/v1/repositories reads one page of public repositories ordered by
 * updated_at descending, and until V5 there was no index supporting it: every
 * request read the whole table and sorted it to return twenty rows.
 *
 * <p>An index cannot change what a query returns, so the interesting questions
 * are the other ones. Does PostgreSQL actually use it - with a literal, and with
 * the bound parameter Hibernate really sends? Does it still select rather than
 * merely order, when public repositories are rare? Do the rows and their order
 * match what the unindexed plan produced?
 *
 * <p>The SQL explained here is not written by hand. It is captured from the
 * statements Hibernate issues for {@code findByVisibility}, so the plan proved
 * below is the plan of the query the application actually runs, and stays that
 * way if the query is ever regenerated differently.
 */
@SpringBootTest(properties = {
        "gitforge.jwt.secret=repo-listing-index-test-signing-secret-of-length",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.gitforge.repo.RepoListingIndexIT$CapturedSql"
})
@Import(TestcontainersConfiguration.class)
class RepoListingIndexIT {

    private static final String INDEX = "ix_repos_visibility_updated";

    /** Enough rows that a sequential scan is not simply the cheapest plan. */
    private static final int REPOS = 20_000;
    private static final int OWNERS = 200;
    private static final int PAGE_SIZE = 20;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RepoRepository repoRepository;

    @Autowired
    private Flyway flyway;

    /** Collects the SQL Hibernate issues, so the real query can be explained. */
    public static class CapturedSql implements StatementInspector {

        static final ConcurrentLinkedQueue<String> STATEMENTS = new ConcurrentLinkedQueue<>();

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql);
            return sql;
        }
    }

    @BeforeEach
    @AfterEach
    void emptyTheTables() {
        execute("DELETE FROM repos");
        execute("DELETE FROM users");
    }

    // --------------------------------------------------------------- seeding

    /**
     * Writes a deterministic population.
     *
     * <p>updated_at is spread by a multiplicative hash rather than following
     * insertion order, so the table is in no useful physical order for this sort
     * and the index has to earn the result.
     */
    private void seed(int repos, int publicPercent) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement users = connection.prepareStatement("""
                    INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
                    SELECT md5('ix-u' || i)::uuid, 'ixuser' || i, 'ixuser' || i || '@example.com',
                           'not-a-real-hash', now(), now()
                    FROM generate_series(1, ?) AS i
                    """)) {
                users.setInt(1, OWNERS);
                users.executeUpdate();
            }
            try (PreparedStatement rows = connection.prepareStatement("""
                    INSERT INTO repos (id, owner_id, name, description, visibility, created_at, updated_at)
                    SELECT md5('ix-r' || i)::uuid,
                           md5('ix-u' || (1 + mod(i, ?)))::uuid,
                           'repo' || i,
                           NULL,
                           CASE WHEN mod(i, 100) < ? THEN 'PUBLIC' ELSE 'PRIVATE' END,
                           now(),
                           timestamptz '2023-01-01 00:00:00+00'
                               + (mod(i * 7919, 1000000) * interval '1 minute')
                    FROM generate_series(1, ?) AS i
                    """)) {
                rows.setInt(1, OWNERS);
                rows.setInt(2, publicPercent);
                rows.setInt(3, repos);
                rows.executeUpdate();
            }
            try (Statement analyze = connection.createStatement()) {
                // Without statistics the planner is guessing, and a plan
                // assertion would be testing the guess rather than the index.
                analyze.execute("ANALYZE repos");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not seed repositories", failure);
        }
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not run " + sql, failure);
        }
    }

    private List<String> query(String sql) {
        List<String> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            while (results.next()) {
                out.add(results.getString(1));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not run " + sql, failure);
        }
        return out;
    }

    // ------------------------------------------------- the application query

    /** Runs the real listing query and returns the SQL Hibernate issued for it. */
    private String listingSql(int page) {
        CapturedSql.STATEMENTS.clear();
        repoRepository.findByVisibility(
                RepoVisibility.PUBLIC,
                PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "updatedAt")));

        return CapturedSql.STATEMENTS.stream()
                .filter(sql -> sql.toLowerCase().contains("from repos"))
                .filter(sql -> sql.toLowerCase().contains("order by"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError(
                        "Hibernate issued no ordered query against repos: " + CapturedSql.STATEMENTS));
    }

    /**
     * The same statement with its parameters written in, so it can be explained.
     *
     * <p>Substituted by shape rather than by position, so a regenerated query
     * that orders its parameters differently still yields a valid statement -
     * and one that cannot be substituted fails loudly instead of being explained
     * as something the application never ran.
     */
    private static String withParameters(String sql, String visibility, int offset, int limit) {
        String filled = sql
                .replaceAll("(?i)(visibility)\\s*=\\s*\\?", "$1='" + visibility + "'")
                .replaceAll("(?i)offset\\s+\\?", "offset " + offset)
                .replaceAll("(?i)fetch\\s+first\\s+\\?", "fetch first " + limit)
                .replaceAll("(?i)limit\\s+\\?", "limit " + limit);

        assertThat(filled)
                .as("every parameter of the captured query was substituted: " + sql)
                .doesNotContain("?");
        return filled;
    }

    private String explain(String sql) {
        return String.join("\n", query("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + sql));
    }

    // ------------------------------------------------------------- migration

    @Nested
    @DisplayName("the migration")
    class Migration {

        @Test
        @DisplayName("creates the index, on both columns, in that order")
        void createsTheIndex() {
            // Read from the catalog rather than matched against the printed
            // definition: the definition contains the index name, so asking
            // whether it mentions "visibility" is answered by the name alone
            // even when the index is on something else entirely.
            assertThat(query("""
                    SELECT a.attname
                    FROM pg_index i
                    JOIN pg_class c ON c.oid = i.indexrelid
                    CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
                    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                    WHERE c.relname = '""" + INDEX + "' ORDER BY k.ord")
            )
                    .as("visibility leads, so the index selects rows and does not only order them")
                    .containsExactly("visibility", "updated_at");
        }

        @Test
        @DisplayName("in the direction the listing reads")
        void inTheReadDirection() {
            List<String> definitions = query(
                    "SELECT indexdef FROM pg_indexes WHERE tablename='repos' AND indexname='"
                            + INDEX + "'");

            assertThat(definitions).hasSize(1);
            assertThat(definitions.getFirst())
                    .contains("ON public.repos")
                    .endsWith("(visibility, updated_at DESC)");
        }

        @Test
        @DisplayName("is recorded once and succeeded")
        void isRecordedOnce() {
            assertThat(query("SELECT success::text FROM flyway_schema_history WHERE version = '5'"))
                    .containsExactly("true");
        }

        @Test
        @DisplayName("re-running migration on an already-migrated database changes nothing")
        void isIdempotentAcrossRestarts() {
            List<String> before = query(
                    "SELECT indexdef FROM pg_indexes WHERE tablename='repos' ORDER BY indexname");

            // What every application start does. Flyway applies versions it has
            // not recorded, so a second and third start must apply nothing.
            flyway.migrate();
            flyway.migrate();

            assertThat(query("SELECT count(*)::text FROM flyway_schema_history WHERE version='5'"))
                    .containsExactly("1");
            assertThat(query("SELECT indexdef FROM pg_indexes WHERE tablename='repos' ORDER BY indexname"))
                    .as("the schema after repeated startup is the schema after one")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("leaves updated_at itself untouched")
        void doesNotChangeTheColumn() {
            // Not nullable, so there is no null-ordering question to answer -
            // and this migration must not have introduced one.
            assertThat(query("""
                    SELECT data_type || ' null=' || is_nullable
                    FROM information_schema.columns
                    WHERE table_name='repos' AND column_name='updated_at'
                    """))
                    .containsExactly("timestamp with time zone null=NO");
        }
    }

    // ---------------------------------------------------------- upgrade path

    @Nested
    @DisplayName("an existing installation being upgraded")
    class Upgrade {

        private static final String SCHEMA = "upgrade_from_v4";

        /**
         * The container database is always created at the newest version, so a
         * fresh start is the only thing the rest of this class can observe.
         *
         * <p>This builds the other case in a schema of its own: migrate to V4
         * and stop, put repositories in it, then migrate the rest of the way -
         * which is what happens to a database that was running before this
         * index existed.
         */
        private Flyway upTo(String version) {
            return Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(SCHEMA)
                    .locations("classpath:db/migration")
                    .target(version == null
                            ? MigrationVersion.LATEST
                            : MigrationVersion.fromVersion(version))
                    .load();
        }

        @AfterEach
        void dropTheSchema() {
            execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }

        @Test
        @DisplayName("gains the index, keeps its repositories, and can be started again")
        void upgradesFromV4() {
            upTo("4").migrate();

            assertThat(indexNames())
                    .as("the index does not exist before the migration that adds it")
                    .doesNotContain(INDEX);

            execute("""
                    INSERT INTO %s.users (id, username, email, password_hash, created_at, updated_at)
                    VALUES (md5('up-u')::uuid, 'upgraded', 'up@example.com', 'x', now(), now())
                    """.formatted(SCHEMA));
            execute("""
                    INSERT INTO %s.repos (id, owner_id, name, visibility, created_at, updated_at)
                    SELECT md5('up-r' || i)::uuid, md5('up-u')::uuid, 'kept' || i, 'PUBLIC',
                           now(), now() - (i * interval '1 hour')
                    FROM generate_series(1, 250) AS i
                    """.formatted(SCHEMA));

            upTo(null).migrate();

            assertThat(indexNames())
                    .as("the upgrade adds the index to a database that already had rows")
                    .contains(INDEX);
            assertThat(query("SELECT count(*)::text FROM " + SCHEMA + ".repos"))
                    .as("and does not disturb a single one of them")
                    .containsExactly("250");
            assertThat(query("SELECT count(*)::text FROM " + SCHEMA
                    + ".repos WHERE updated_at IS NULL"))
                    .as("nor rewrite the column it indexes")
                    .containsExactly("0");

            // Starting the application again applies nothing further.
            upTo(null).migrate();
            assertThat(query("SELECT count(*)::text FROM " + SCHEMA
                    + ".flyway_schema_history WHERE version='5'"))
                    .containsExactly("1");
            assertThat(query("SELECT count(*)::text FROM " + SCHEMA
                    + ".flyway_schema_history WHERE success = false"))
                    .as("no migration in the history failed")
                    .containsExactly("0");
        }

        private List<String> indexNames() {
            return query("SELECT indexname FROM pg_indexes WHERE schemaname='" + SCHEMA
                    + "' AND tablename='repos'");
        }
    }

    // ----------------------------------------------------------- index usage

    @Nested
    @DisplayName("PostgreSQL uses the index for the listing")
    class PlanUsage {

        @Test
        @DisplayName("for the first page")
        void forTheFirstPage() {
            seed(REPOS, 70);
            String plan = explain(withParameters(listingSql(0), "PUBLIC", 0, PAGE_SIZE));

            assertThat(plan).contains(INDEX);
            assertThat(plan)
                    .as("the whole table is no longer read to return one page:\n" + plan)
                    .doesNotContain("Seq Scan on repos");
        }

        @Test
        @DisplayName("for a deep page, without sorting the table")
        void forADeepPage() {
            seed(REPOS, 70);
            String plan = explain(withParameters(listingSql(0), "PUBLIC", 1_000, PAGE_SIZE));

            assertThat(plan).contains(INDEX);
            assertThat(plan).doesNotContain("Seq Scan on repos");
            assertThat(plan)
                    .as("rows arrive in order, so nothing is sorted at query time:\n" + plan)
                    .doesNotContain("Sort Method");
        }

        @Test
        @DisplayName("when public repositories are rare, it still selects and not merely orders")
        void whenPublicRepositoriesAreRare() {
            // The case that decides the shape of the index. Ordering by
            // updated_at alone would walk down the whole table in date order
            // discarding private repositories, and the rarer the public ones are
            // the further it walks. With visibility leading, nothing is discarded.
            seed(REPOS, 1);
            String plan = explain(withParameters(listingSql(0), "PUBLIC", 0, PAGE_SIZE));

            assertThat(plan).contains(INDEX);
            assertThat(plan).doesNotContain("Seq Scan on repos");
            assertThat(plan)
                    .as("no row is read only to be thrown away:\n" + plan)
                    .doesNotContain("Rows Removed by Filter");
        }

        @Test
        @DisplayName("with a bound parameter, once the planner has settled on a generic plan")
        void withABoundParameter() throws SQLException {
            // Hibernate sends the visibility as a parameter, not a literal. After
            // five executions PostgreSQL may stop re-planning per value, so the
            // index has to be usable without knowing what the value is.
            seed(REPOS, 70);
            String prepared = withParameters(listingSql(0), "PUBLIC", 0, PAGE_SIZE)
                    .replace("'PUBLIC'", "$1");

            StringBuilder plan = new StringBuilder();
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {

                statement.execute("PREPARE gf_listing(varchar) AS " + prepared);
                for (int run = 0; run < 5; run++) {
                    statement.execute("EXECUTE gf_listing('PUBLIC')");
                }
                try (ResultSet rows = statement.executeQuery(
                        "EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE gf_listing('PUBLIC')")) {
                    while (rows.next()) {
                        plan.append(rows.getString(1)).append('\n');
                    }
                }
                statement.execute("DEALLOCATE gf_listing");
            }

            assertThat(plan.toString())
                    .as("the sixth execution still reaches the index:\n" + plan)
                    .contains(INDEX);
        }
    }

    // ----------------------------------------------------------- equivalence

    @Nested
    @DisplayName("the answer is the one the unindexed query gave")
    class Equivalence {

        /**
         * The same query the application makes, planned as it was before the
         * index existed.
         *
         * <p>Index scans are turned off for this connection only, which leaves
         * the planner exactly the options it had in V4: read the table, sort it.
         */
        private List<String> unindexed(String sql) {
            List<String> ids = new ArrayList<>();
            try (Connection connection = dataSource.getConnection()) {
                // SET LOCAL and a rollback, because the connection goes back to
                // the pool afterwards. A plain SET would follow it there and
                // quietly disable index scans for whatever ran next.
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL enable_indexscan = off");
                    statement.execute("SET LOCAL enable_bitmapscan = off");
                    statement.execute("SET LOCAL enable_indexonlyscan = off");
                    try (ResultSet rows = statement.executeQuery(sql)) {
                        while (rows.next()) {
                            ids.add(rows.getString("id"));
                        }
                    }
                } finally {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not run the reference query", failure);
            }
            return ids;
        }

        private List<String> indexed(int page) {
            return repoRepository.findByVisibility(
                            RepoVisibility.PUBLIC,
                            PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "updatedAt")))
                    .getContent().stream()
                    .map(repo -> repo.getId().toString())
                    .toList();
        }

        @Test
        @DisplayName("page after page, exactly and in order")
        void pageAfterPage() {
            seed(REPOS, 70);

            for (int page : new int[]{0, 1, 7, 50, 200}) {
                // Captured per page rather than once: Spring omits the offset
                // clause entirely for page 0, so one captured statement cannot
                // stand in for the others.
                List<String> reference = unindexed(
                        withParameters(listingSql(page), "PUBLIC", page * PAGE_SIZE, PAGE_SIZE));

                assertThat(reference).as("the reference returned a page " + page).isNotEmpty();
                assertThat(indexed(page))
                        .as("page " + page)
                        .containsExactlyElementsOf(reference);
            }
        }

        @Test
        @DisplayName("the statement being explained is the one the application sends")
        void theCapturedStatementIsTheRealOne() {
            seed(100, 100);
            String firstPage = listingSql(0);
            String deepPage = listingSql(3);

            System.out.println("  page 0: " + firstPage);
            System.out.println("  page 3: " + deepPage);

            assertThat(firstPage.toLowerCase())
                    .contains("from repos")
                    .contains("visibility=?")
                    .contains("order by")
                    .contains("updated_at desc");
            assertThat(deepPage.toLowerCase())
                    .as("a page past the first carries an offset, which page 0 does not")
                    .contains("offset ?");
        }

        @Test
        @DisplayName("on an empty table")
        void onAnEmptyTable() {
            assertThat(indexed(0)).isEmpty();
            assertThat(repoRepository.findByVisibility(
                            RepoVisibility.PUBLIC, PageRequest.of(0, PAGE_SIZE))
                    .getTotalElements())
                    .isZero();
        }

        @Test
        @DisplayName("on one repository, and on one that is private")
        void onOneRepository() {
            seed(1, 100);
            assertThat(indexed(0)).hasSize(1);

            execute("UPDATE repos SET visibility='PRIVATE'");
            execute("ANALYZE repos");
            assertThat(indexed(0))
                    .as("a private repository never appears in the public listing")
                    .isEmpty();
        }

        @Test
        @DisplayName("when every timestamp is equal, both plans return the same rows")
        void whenTimestampsAreEqual() {
            seed(500, 100);
            execute("UPDATE repos SET updated_at = timestamptz '2024-06-01 00:00:00+00'");
            execute("ANALYZE repos");

            String sql = withParameters(listingSql(0), "PUBLIC", 0, 500);
            List<String> reference = unindexed(sql);
            // The id is the first column Hibernate selects, which is what query reads.
            List<String> withIndex = query(sql);

            assertThat(withIndex)
                    .as("the same 500 repositories, however the ties fall")
                    .containsExactlyInAnyOrderElementsOf(reference);

            // What is deliberately NOT asserted: that paging through ties visits
            // each row once. ORDER BY updated_at has no tiebreaker, so a tied
            // block has no defined order and successive OFFSETs may re-show one
            // row and never show another. Measured on 500 tied rows, the plan
            // this endpoint used before V5 returned 500 rows of which only 489
            // were distinct. That is a property of the query, not of the index,
            // and this window does not change the query.
        }

        @Test
        @DisplayName("descending order is genuinely descending")
        void isOrderedDescending() {
            seed(2_000, 100);
            List<String> timestamps = query("""
                    SELECT updated_at::text FROM repos WHERE visibility='PUBLIC'
                    ORDER BY updated_at DESC FETCH FIRST 500 ROWS ONLY
                    """);

            assertThat(timestamps).hasSize(500);
            for (int i = 1; i < timestamps.size(); i++) {
                assertThat(timestamps.get(i))
                        .as("row " + i + " is not newer than the one before it")
                        .isLessThanOrEqualTo(timestamps.get(i - 1));
            }
        }
    }

    // ------------------------------------------------------------- freshness

    @Nested
    @DisplayName("the listing is never stale")
    class Freshness {

        private String idOf(String name) {
            List<String> found = query("SELECT id::text FROM repos WHERE name='" + name + "'");
            return found.isEmpty() ? "absent" : found.getFirst();
        }

        private List<String> firstPage() {
            return repoRepository.findByVisibility(
                            RepoVisibility.PUBLIC,
                            PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "updatedAt")))
                    .getContent().stream()
                    .map(repo -> repo.getId().toString())
                    .toList();
        }

        @Test
        @DisplayName("an insert appears, an update moves it, a delete removes it")
        void reflectsEveryMutation() {
            seed(1_000, 100);
            long before = repoRepository.count();

            execute("""
                    INSERT INTO repos (id, owner_id, name, visibility, created_at, updated_at)
                    VALUES (md5('ix-new')::uuid, md5('ix-u1')::uuid, 'brand-new', 'PUBLIC',
                            now(), timestamptz '2099-01-01 00:00:00+00')
                    """);

            assertThat(repoRepository.count()).isEqualTo(before + 1);
            assertThat(firstPage().getFirst())
                    .as("the newest repository leads the listing straight away")
                    .isEqualTo(idOf("brand-new"));

            execute("UPDATE repos SET updated_at = timestamptz '2000-01-01 00:00:00+00' "
                    + "WHERE name='brand-new'");
            assertThat(firstPage())
                    .as("and leaves the front when its timestamp does")
                    .doesNotContain(idOf("brand-new"));

            execute("DELETE FROM repos WHERE name='brand-new'");
            assertThat(repoRepository.count()).isEqualTo(before);
        }

        @Test
        @DisplayName("a rolled-back write leaves nothing behind")
        void ignoresRolledBackWrites() {
            seed(500, 100);
            long before = repoRepository.count();

            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            INSERT INTO repos (id, owner_id, name, visibility, created_at, updated_at)
                            VALUES (md5('ix-rolled')::uuid, md5('ix-u1')::uuid, 'rolled-back',
                                    'PUBLIC', now(), timestamptz '2099-01-01 00:00:00+00')
                            """);
                }
                connection.rollback();
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not exercise a rollback", failure);
            }

            assertThat(repoRepository.count())
                    .as("an index cannot claim a row the transaction abandoned")
                    .isEqualTo(before);
            assertThat(firstPage()).doesNotContain(idOf("rolled-back"));
        }
    }

    // ---------------------------------------------------------- measurement

    @Nested
    @DisplayName("what the index is worth")
    class Measurements {

        /**
         * Nothing here asserts on a duration.
         *
         * <p>A test that fails when a machine is busy is not a correctness test,
         * and the assertions that matter - that the index is used, and that the
         * rows are unchanged - are made elsewhere without reference to a clock.
         * This reports, and leaves the judgement to whoever reads it.
         */
        @Test
        @DisplayName("the public listing, before and after")
        void listingLatency() {
            int rows = 50_000;
            seed(rows, 70);

            String page0 = withParameters(listingSql(0), "PUBLIC", 0, PAGE_SIZE);
            String deep = withParameters(listingSql(5), "PUBLIC", 1_000, PAGE_SIZE);
            String counting = countSql();

            System.out.println("\n=== repos.visibility/updated_at index: " + rows + " repositories ===");
            report("first page", page0);
            report("page 50", deep);
            report("the count Spring Data issues alongside every page", counting);

            // A write, then the listing again: whatever the index costs to keep
            // current, the next read must not be paying a rebuild.
            execute("UPDATE repos SET updated_at = now() WHERE mod(abs(hashtext(name)), 10) = 0");
            System.out.println("  after updating a tenth of the table:");
            report("first page", page0);
        }

        /** The count query Spring Data issues to build a Page. */
        private String countSql() {
            CapturedSql.STATEMENTS.clear();
            repoRepository.findByVisibility(RepoVisibility.PUBLIC, PageRequest.of(0, PAGE_SIZE));
            String captured = CapturedSql.STATEMENTS.stream()
                    .filter(sql -> sql.toLowerCase().contains("count("))
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new AssertionError("no count query was issued"));
            return withParameters(captured, "PUBLIC", 0, PAGE_SIZE);
        }

        private void report(String what, String sql) {
            long before = median(sql, true);
            long after = median(sql, false);
            System.out.printf("  %-52s %6.2f ms -> %6.2f ms  (%s)%n",
                    what, before / 1_000_000.0, after / 1_000_000.0,
                    after == 0 ? "immeasurable" : String.format("%.1fx", before / (double) after));
        }

        /**
         * Median of seven runs.
         *
         * <p>{@code baseline} runs the statement with index scans turned off for
         * that transaction only, which leaves the planner the options it had
         * before V5 existed: read the table, sort it.
         */
        private long median(String sql, boolean baseline) {
            long[] runs = new long[7];
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    if (baseline) {
                        statement.execute("SET LOCAL enable_indexscan = off");
                        statement.execute("SET LOCAL enable_bitmapscan = off");
                        statement.execute("SET LOCAL enable_indexonlyscan = off");
                    }
                    for (int run = 0; run < runs.length; run++) {
                        long started = System.nanoTime();
                        try (ResultSet result = statement.executeQuery(sql)) {
                            while (result.next()) {
                                result.getString(1);
                            }
                        }
                        runs[run] = System.nanoTime() - started;
                    }
                } finally {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not measure " + sql, failure);
            }
            java.util.Arrays.sort(runs);
            return runs[runs.length / 2];
        }
    }

    // ----------------------------------------------------------- concurrency

    @Nested
    @DisplayName("writes and listings run together")
    class Concurrency {

        @Test
        @DisplayName("listing while repositories are created and updated")
        void whileTheTableIsBeingWritten() throws Exception {
            seed(5_000, 70);

            int writers = 4;
            int readers = 4;
            int perWriter = 25;
            ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            for (int w = 0; w < writers; w++) {
                int writer = w;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perWriter; i++) {
                            String name = "live-" + writer + "-" + i;
                            execute("INSERT INTO repos "
                                    + "(id, owner_id, name, visibility, created_at, updated_at) "
                                    + "VALUES (gen_random_uuid(), md5('ix-u1')::uuid, '" + name
                                    + "', 'PUBLIC', now(), now())");
                            execute("UPDATE repos SET updated_at = now() WHERE name = '"
                                    + name + "'");
                        }
                    } catch (Throwable thrown) {
                        failure.compareAndSet(null, thrown);
                    }
                });
            }
            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 40; i++) {
                            Page<Repo> page = repoRepository.findByVisibility(
                                    RepoVisibility.PUBLIC,
                                    PageRequest.of(i % 5, PAGE_SIZE,
                                            Sort.by(Sort.Direction.DESC, "updatedAt")));

                            assertThat(page.getContent()).hasSizeLessThanOrEqualTo(PAGE_SIZE);
                            for (Repo repo : page.getContent()) {
                                assertThat(repo.getVisibility()).isEqualTo(RepoVisibility.PUBLIC);
                                assertThat(repo.getUpdatedAt()).isNotNull();
                            }
                        }
                    } catch (Throwable thrown) {
                        failure.compareAndSet(null, thrown);
                    }
                });
            }

            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(2, TimeUnit.MINUTES))
                    .as("no reader or writer was left blocked")
                    .isTrue();
            if (failure.get() != null) {
                throw new AssertionError("A concurrent listing or write failed", failure.get());
            }

            assertThat(query("SELECT count(*)::text FROM repos WHERE name LIKE 'live-%'"))
                    .as("every concurrently created repository is present exactly once")
                    .containsExactly(String.valueOf(writers * perWriter));
        }
    }
}
