package com.ashish.stockresearch;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * The tests' PostgreSQL: one pgvector container for the whole test run (Docker must be running), and a fresh
 * database in it for every caller, migrated by the application's own Flyway scripts - so each test is as isolated as
 * it was with an in-memory database, on the same database engine the application runs on.
 */
public final class TestPostgres {

    public static final DockerImageName IMAGE = DockerImageName.parse("pgvector/pgvector:pg17")
            .asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer container;

    private TestPostgres() {
    }

    /** The shared container, started on first use; Testcontainers removes it when the test JVM exits. */
    public static synchronized PostgreSQLContainer container() {
        if (container == null) {
            PostgreSQLContainer started = new PostgreSQLContainer(IMAGE)
                    // a database per test class instance, each with a small pool; durability is not needed in a test
                    .withCommand("postgres", "-c", "max_connections=400", "-c", "fsync=off",
                            "-c", "synchronous_commit=off", "-c", "full_page_writes=off");
            started.start();
            container = started;
        }
        return container;
    }

    /** The JDBC URL of a new, empty database, with no migration applied: for an application context's own Flyway. */
    public static String newDatabaseUrl() {
        PostgreSQLContainer postgres = container();
        String name = "t_" + UUID.randomUUID().toString().replace("-", "");
        admin(postgres, "CREATE DATABASE " + name);
        return postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
    }

    /** A new, empty database with every migration applied. {@link EmbeddedDatabase#shutdown()} drops it. */
    public static EmbeddedDatabase newDatabase() {
        PostgreSQLContainer postgres = container();
        String url = newDatabaseUrl();
        String name = url.replaceFirst(".*/([^/?]+).*", "$1");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setIdleTimeout(10_000);
        HikariDataSource pool = new HikariDataSource(config);
        Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate();
        return new Database(pool, name);
    }

    private static void admin(PostgreSQLContainer postgres, String sql) {
        try (Connection connection = postgres.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException ex) {
            throw new IllegalStateException(sql, ex);
        }
    }

    private static final class Database extends DelegatingDataSource implements EmbeddedDatabase {

        private final HikariDataSource pool;
        private final String name;

        Database(HikariDataSource pool, String name) {
            super(pool);
            this.pool = pool;
            this.name = name;
        }

        @Override
        public void shutdown() {
            pool.close();
            admin(container(), "DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }
}
