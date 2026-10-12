package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.TestPostgres;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

/** A fresh PostgreSQL database with the application's own schema (its Flyway migrations); shutdown() drops it. */
public final class SnapshotDatabase {

    private SnapshotDatabase() {
    }

    public static EmbeddedDatabase create() {
        return TestPostgres.newDatabase();
    }
}
