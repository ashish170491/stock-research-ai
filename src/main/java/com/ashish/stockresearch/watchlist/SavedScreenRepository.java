package com.ashish.stockresearch.watchlist;

import com.ashish.stockresearch.screening.Universe;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Stores saved screens (S8-6): a name, re-saving it replaces its universe, preset and industry group. */
@Repository
public class SavedScreenRepository {

    private final JdbcClient jdbc;

    public SavedScreenRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public SavedScreen save(String name, Universe universe, String preset, String industryGroup) {
        Optional<SavedScreen> existing = findByName(name);
        if (existing.isPresent()) {
            jdbc.sql("""
                            UPDATE saved_screen SET universe = :universe, preset = :preset,
                                   industry_group = :group WHERE name = :name""")
                    .param("universe", universe.name()).param("preset", preset).param("group", industryGroup)
                    .param("name", name)
                    .update();
            return new SavedScreen(existing.get().id(), name, universe, preset, industryGroup,
                    existing.get().createdAt());
        }
        Instant now = Instant.now();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO saved_screen (name, universe, preset, industry_group, created_at)
                        VALUES (:name, :universe, :preset, :group, :created)""")
                .param("name", name).param("universe", universe.name()).param("preset", preset)
                .param("group", industryGroup).param("created", now.atOffset(ZoneOffset.UTC))
                .update(key, "id");
        return new SavedScreen(key.getKey().longValue(), name, universe, preset, industryGroup, now);
    }

    public List<SavedScreen> findAll() {
        return jdbc.sql("SELECT * FROM saved_screen ORDER BY name").query(SavedScreenRepository::row).list();
    }

    public Optional<SavedScreen> findByName(String name) {
        return jdbc.sql("SELECT * FROM saved_screen WHERE name = :name").param("name", name)
                .query(SavedScreenRepository::row).optional();
    }

    private static SavedScreen row(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        return new SavedScreen(rs.getLong("id"), rs.getString("name"), Universe.valueOf(rs.getString("universe")),
                rs.getString("preset"), rs.getString("industry_group"),
                createdAt == null ? null : createdAt.toInstant());
    }
}
