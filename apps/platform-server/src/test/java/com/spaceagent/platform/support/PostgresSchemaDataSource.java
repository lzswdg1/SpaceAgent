package com.spaceagent.platform.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Creates an isolated database because historical migrations intentionally address public. */
public final class PostgresSchemaDataSource {

    private PostgresSchemaDataSource() {
    }

    public static synchronized DriverManagerDataSource forSchema(
            PostgreSQLContainer<?> postgres,
            String database) {
        if (database == null || !database.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Invalid PostgreSQL test database name");
        }
        DriverManagerDataSource administrator = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Long present = new JdbcTemplate(administrator).queryForObject(
                "SELECT count(*) FROM pg_database WHERE datname = ?", Long.class, database);
        if (present == null || present == 0) {
            new JdbcTemplate(administrator).execute("CREATE DATABASE \"" + database + "\"");
        }
        String sourceUrl = postgres.getJdbcUrl();
        int query = sourceUrl.indexOf('?');
        String suffix = query < 0 ? "" : sourceUrl.substring(query);
        String path = query < 0 ? sourceUrl : sourceUrl.substring(0, query);
        String databaseUrl = path.substring(0, path.lastIndexOf('/') + 1) + database + suffix;
        return new DriverManagerDataSource(
                databaseUrl,
                postgres.getUsername(),
                postgres.getPassword());
    }
}
