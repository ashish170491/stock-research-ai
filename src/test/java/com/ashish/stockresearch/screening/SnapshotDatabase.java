package com.ashish.stockresearch.screening;

import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.UUID;

/** A fresh in-memory H2 database with the application's own schema. */
final class SnapshotDatabase {

    private SnapshotDatabase() {
    }

    static EmbeddedDatabase create() {
        return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("snapshot-" + UUID.randomUUID())
                .addScript("classpath:db/screening-schema.sql").build();
    }
}
