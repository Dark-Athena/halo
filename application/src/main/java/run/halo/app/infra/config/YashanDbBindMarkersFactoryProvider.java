package run.halo.app.infra.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.core.binding.BindMarkersFactory;
import org.springframework.r2dbc.core.binding.BindMarkersFactoryResolver;

/**
 * Spring Framework {@link BindMarkersFactoryResolver.BindMarkerFactoryProvider} for YashanDB.
 *
 * <p>YashanDB supports {@code :name} style parameter binding (Oracle-compatible).
 * This provider returns a {@link BindMarkersFactory} using Oracle-style named
 * markers ({@code :Pname} format), matching the pattern that the r2dbc-yashandb
 * driver's statement implementation parses.
 *
 * <p>This class is registered via {@code META-INF/spring.factories} as an
 * implementation of {@link BindMarkersFactoryResolver.BindMarkerFactoryProvider}.
 * The {@code spring.factories} SPI is the only mechanism that works because
 * {@link BindMarkersFactoryResolver} loads providers statically at class
 * initialisation time; a user-defined {@code @Bean DatabaseClient} is an
 * alternative but requires manually building the {@code DatabaseClient}.
 */
public class YashanDbBindMarkersFactoryProvider
        implements BindMarkersFactoryResolver.BindMarkerFactoryProvider {

    private static final String YASHANDB_NAME = "YashanDB";

    /**
     * Maximum identifier length for an Oracle-style named bind-marker suffix.
     * Matches Spring Framework's built-in Oracle {@code BindMarkersFactoryProvider}.
     */
    private static final int MAX_NAME_LENGTH = 32;

    @Override
    public BindMarkersFactory getBindMarkers(ConnectionFactory connectionFactory) {
        String name = connectionFactory.getMetadata().getName();
        if (name != null && name.contains(YASHANDB_NAME)) {
            return BindMarkersFactory.named(":", "P", MAX_NAME_LENGTH,
                YashanDbBindMarkersFactoryProvider::filterName);
        }
        return null;
    }

    /**
     * Sanitises a parameter name so it can be embedded in an Oracle named-marker
     * ({@code :Pname}).  Strips all characters that are not ASCII letters or
     * digits and prepends {@code _}.  Matches the sanitisation used by Spring
     * Framework's built-in Oracle {@code BindMarkersFactoryProvider}.
     */
    private static String filterName(CharSequence input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c) && c < 127) {
                sb.append(c);
            }
        }
        if (sb.isEmpty()) {
            return "";
        }
        return "_" + sb;
    }
}
