package run.halo.app.infra.config;

import io.r2dbc.spi.Connection;
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

/**
 * Provides r2dbc-migrate {@link SqlQueries} and {@link Locker} beans that use
 * YashanDB-compatible (Oracle-syntax) SQL for the migration-tracking tables.
 *
 * <p>Active only when {@code spring.sql.init.platform=yashandb}.  The YAML profile
 * overrides {@code r2dbc.migrate.dialect} to {@code mysql} so that Spring Boot can
 * bind the property to the {@code Dialect} enum without error; the actual DDL and
 * DML executed by r2dbc-migrate is entirely controlled by these beans.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.sql.init.platform", havingValue = "yashandb")
class YashanDbMigrateConfiguration {

    @Bean
    SqlQueries yashanDbSqlQueries(R2dbcMigrateProperties properties) {
        var table = tableRef(properties.getMigrationsSchema(), properties.getMigrationsTable());
        return new SqlQueries() {

            @Override
            public List<String> createInternalTables() {
                return List.of(
                    "create table if not exists " + table
                        + " (id number primary key, description varchar2(4000))"
                );
            }

            @Override
            public String getMaxMigration() {
                return "select max(id) from " + table;
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
    Locker yashanDbLocker(R2dbcMigrateProperties properties) {
        var lockTable =
            tableRef(properties.getMigrationsSchema(), properties.getMigrationsLockTable());
        var lockTableName = properties.getMigrationsLockTable();
        return new AbstractTableLocker() {

            @Override
            public List<String> createInternalTables() {
                return List.of(
                    "create table if not exists " + lockTable
                        + " (id number not null, locked number(1) not null,"
                        + " constraint pk_" + lockTableName + " primary key (id))",
                    // Insert the initial row only if it does not already exist.
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

    private static String tableRef(String schema, String table) {
        if (schema != null && !schema.isBlank()) {
            return schema + "." + table;
        }
        return table;
    }
}
