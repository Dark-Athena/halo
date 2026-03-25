package run.halo.app.infra.config;

import io.r2dbc.spi.ConnectionFactory;
import java.util.Optional;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.data.r2dbc.dialect.OracleDialect;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;

/**
 * Spring Data R2DBC {@link DialectResolver.R2dbcDialectProvider} for YashanDB.
 *
 * <p>YashanDB is an Oracle-compatible database: it supports Oracle SQL syntax,
 * Oracle-style pagination ({@code FETCH FIRST n ROWS ONLY}), and Oracle data
 * types ({@code VARCHAR2}, {@code BLOB}, {@code NUMBER}).  Therefore
 * {@link OracleDialect#INSTANCE} is used as the dialect.
 *
 * <p>This class is registered via {@code META-INF/spring.factories} as an
 * implementation of {@link DialectResolver.R2dbcDialectProvider}.  That is the
 * only mechanism that works: {@link DialectResolver#getDialect} is called in
 * the constructor of {@code DataR2dbcAutoConfiguration} — before any Spring
 * beans are created — so a user-defined {@code @Bean R2dbcDialect} has no
 * effect on that call.
 */
public class YashanDbDialectProvider implements DialectResolver.R2dbcDialectProvider {

    private static final String YASHANDB_NAME = "YashanDB";

    @Override
    public Optional<R2dbcDialect> getDialect(ConnectionFactory connectionFactory) {
        String name = connectionFactory.getMetadata().getName();
        if (name != null && name.contains(YASHANDB_NAME)) {
            return Optional.of(OracleDialect.INSTANCE);
        }
        return Optional.empty();
    }
}
