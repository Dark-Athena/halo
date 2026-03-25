package run.halo.app.infra.config;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Statement;
import java.util.List;
import name.nkonev.r2dbc.migrate.core.AbstractTableLocker;
import name.nkonev.r2dbc.migrate.core.Locker;
import name.nkonev.r2dbc.migrate.core.MigrationMetadata;
import name.nkonev.r2dbc.migrate.core.R2dbcMigrateProperties;
import name.nkonev.r2dbc.migrate.core.SqlQueries;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.r2dbc.dialect.OracleDialect;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.binding.BindMarkersFactory;

/**
 * Provides r2dbc-migrate {@link SqlQueries} and {@link Locker} beans that use
 * YashanDB-compatible (Oracle-syntax) SQL for the migration-tracking tables.
 *
 * <p>Active only when {@code spring.sql.init.platform=yashandb}.  The YAML profile
 * overrides {@code r2dbc.migrate.dialect} to {@code mysql} so that Spring Boot can
 * bind the property to the {@code Dialect} enum without error; the actual DDL and
 * DML executed by r2dbc-migrate is entirely controlled by these beans.
 *
 * <p>YashanDB does not support {@code CREATE TABLE IF NOT EXISTS} — the statement is
 * silently ignored (a warning, not an error) so the tables are never actually created.
 * To work around this, the migration-tracking tables ({@code migrations} and
 * {@code migrations_lock}) are pre-created by Spring's SQL initializer via
 * {@code schema-yashandb.sql} (with {@code spring.sql.init.continue-on-error=true} to
 * absorb "table already exists" errors on restart).  Both beans therefore declare
 * {@code @DependsOn("r2dbcScriptDatabaseInitializer")} to guarantee that the schema
 * script runs before r2dbc-migrate attempts to use the tables.
 *
 * <p>Spring Framework's {@code BindMarkersFactoryResolver} and Spring Data R2DBC's
 * {@code DialectResolver} do not know about "YashanDB" — both use a {@code spring.factories}
 * SPI that has built-in entries only for H2, MySQL, MariaDB, PostgreSQL, Oracle, and
 * MSSQL.  Since YashanDB is Oracle-compatible, we provide a {@link DatabaseClient} bean
 * configured with Oracle-style named bind markers and an {@link R2dbcDialect} bean
 * using {@link OracleDialect#INSTANCE}.  These user-defined beans take precedence over
 * Spring Boot's {@code @ConditionalOnMissingBean} auto-configurations.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.sql.init.platform", havingValue = "yashandb")
class YashanDbMigrateConfiguration {

    /**
     * Maximum length for the sanitised portion of an Oracle-style named bind-marker name
     * (e.g. the {@code name} part in {@code :Pname}).  Matches the limit used by
     * Spring Framework's built-in Oracle {@code BindMarkersFactoryProvider}.
     */
    private static final int BIND_MARKER_NAME_MAX_LENGTH = 32;

    /**
     * Provides a {@link DatabaseClient} configured with Oracle-style named bind markers
     * ({@code :Pname} format).  YashanDB is Oracle-compatible and its R2DBC driver
     * supports {@code :name} style parameter binding (as proven by r2dbc-migrate using
     * {@code :id}, {@code :descr} parameters successfully).
     *
     * <p>This bean overrides Spring Boot's auto-configured {@code DatabaseClient} which
     * would call {@code BindMarkersFactoryResolver.resolve(connectionFactory)} — a call
     * that throws {@code NoBindMarkersFactoryException} because "YashanDB" is not in
     * Spring Framework's built-in provider list.
     */
    @Bean
    DatabaseClient r2dbcDatabaseClient(ConnectionFactory connectionFactory) {
        return DatabaseClient.builder()
            .connectionFactory(connectionFactory)
            .bindMarkers(BindMarkersFactory.named(":", "P", BIND_MARKER_NAME_MAX_LENGTH,
                YashanDbMigrateConfiguration::filterBindMarkerName))
            .build();
    }

    /**
     * Provides an Oracle-compatible {@link R2dbcDialect} for Spring Data R2DBC.
     *
     * <p>{@link OracleDialect#INSTANCE} is used because YashanDB targets Oracle
     * compatibility: it understands Oracle-style pagination ({@code FETCH FIRST n ROWS
     * ONLY}), Oracle SQL types ({@code VARCHAR2}, {@code BLOB}, {@code NUMBER}), and
     * Oracle-style named parameter binding.  This bean overrides Spring Boot's
     * auto-configured dialect, which would call {@code DialectResolver.getDialect()}
     * and fail because "YashanDB" is not registered with the built-in dialect provider.
     */
    @Bean
    R2dbcDialect r2dbcDialect() {
        return OracleDialect.INSTANCE;
    }

    @Bean
    @DependsOn("r2dbcScriptDatabaseInitializer")
    SqlQueries yashanDbSqlQueries(R2dbcMigrateProperties properties) {
        var table = tableRef(properties.getMigrationsSchema(), properties.getMigrationsTable());
        return new SqlQueries() {

            @Override
            public List<String> createInternalTables() {
                // Tables are pre-created by Spring SQL init (schema-yashandb.sql).
                // YashanDB does not support CREATE TABLE IF NOT EXISTS so we return an
                // empty list here to avoid a DDL statement that would be silently ignored.
                return List.of();
            }

            @Override
            public String getMaxMigration() {
                // Use an explicit column alias so getResultSafely("max", ...) can locate it.
                // The r2dbc spec guarantees case-insensitive matching for RowMetadata.contains(),
                // so "max" matches the uppercase alias that Oracle/YashanDB produces.
                return "select max(id) as max from " + table;
            }

            @Override
            public Statement createInsertMigrationStatement(Connection connection,
                                                            MigrationMetadata migrationInfo) {
                return connection
                    .createStatement(
                        "insert into " + table + "(id, description) values (:id, :descr)")
                    .bind("id", migrationInfo.getVersion())
                    .bind("descr", migrationInfo.getDescription());
            }
        };
    }

    @Bean
    @DependsOn("r2dbcScriptDatabaseInitializer")
    Locker yashanDbLocker(R2dbcMigrateProperties properties) {
        var lockTable =
            tableRef(properties.getMigrationsSchema(), properties.getMigrationsLockTable());
        return new AbstractTableLocker() {

            @Override
            public List<String> createInternalTables() {
                // The migrations_lock table is pre-created by Spring SQL init
                // (schema-yashandb.sql).  We only need to ensure the initial lock row (id=1)
                // exists; the INSERT is idempotent via WHERE NOT EXISTS.
                return List.of(
                    "insert into " + lockTable + " (id, locked)"
                        + " select 1, 0 from dual"
                        + " where not exists (select 1 from " + lockTable + " where id = 1)"
                );
            }

            @Override
            public Statement tryAcquireLock(Connection connection) {
                return connection.createStatement(
                    "update " + lockTable + " set locked = 1 where id = 1 and locked = 0");
            }

            @Override
            public Statement releaseLock(Connection connection) {
                return connection.createStatement(
                    "update " + lockTable + " set locked = 0 where id = 1");
            }
        };
    }

    /**
     * Filters a parameter name to ASCII letters and digits, prepending {@code _} so the
     * result can safely be embedded in an Oracle named-parameter marker ({@code :Pname}).
     * This matches the sanitisation logic used by Spring Framework's built-in Oracle
     * {@code BindMarkersFactoryProvider}.
     */
    private static String filterBindMarkerName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) && c < 127) {
                sb.append(c);
            }
        }
        if (sb.isEmpty()) {
            throw new IllegalArgumentException(
                "Parameter name '" + name + "' contains no ASCII alphanumeric characters "
                    + "and cannot be used as an Oracle bind-marker name");
        }
        return "_" + sb;
    }

    private static String tableRef(String schema, String table) {
        if (schema != null && !schema.isBlank()) {
            return schema + "." + table;
        }
        return table;
    }
}
