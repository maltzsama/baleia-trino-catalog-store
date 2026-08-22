package io.baleia.trino.catalogstore;

import com.google.inject.Injector;
import io.airlift.bootstrap.Bootstrap;
import io.airlift.log.Logger;
import io.trino.spi.catalog.CatalogStore;
import io.trino.spi.catalog.CatalogStoreFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import static io.airlift.configuration.ConfigBinder.configBinder;

/**
 * Guice-wired factory that produces a {@link BaleiaCatalogStore}.
 *
 * <p>Trino discovers this factory by name ({@code "baleia"}) via
 * {@code catalog.store=baleia} in {@code etc/config.properties}. The factory
 * receives its configuration from {@code etc/catalog-store.properties} and
 * wires the full dependency graph: {@link Database}, {@link SecretResolver},
 * and {@link BaleiaCatalogStore} as singletons.
 *
 * <p>At startup the factory logs the compiled and detected SPI versions to
 * aid troubleshooting version mismatches at boot time.
 *
 * @see BaleiaCatalogStorePlugin
 * @see BaleiaCatalogStore
 */
public class BaleiaCatalogStoreFactory
        implements CatalogStoreFactory
{
    private static final Logger log = Logger.get(BaleiaCatalogStoreFactory.class);

    private static final String COMPILED_TRINO_VERSION;

    static
    {
        String version = "unknown";
        try (InputStream is = BaleiaCatalogStoreFactory.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                version = props.getProperty("compiled.trino.version", "unknown");
            }
        }
        catch (IOException ignored) {
        }
        COMPILED_TRINO_VERSION = version;
    }

    @Override
    public String getName()
    {
        return "baleia";
    }

    @Override
    public CatalogStore create(Map<String, String> config)
    {
        String detectedVersion = CatalogStore.class.getPackage().getImplementationVersion();
        log.info("baleia-catalog-store starting; compiled against trino-spi %s, detected: %s",
                COMPILED_TRINO_VERSION,
                detectedVersion == null ? "unknown" : detectedVersion);

        Bootstrap app = new Bootstrap(binder -> {
            configBinder(binder).bindConfig(BaleiaCatalogStoreConfig.class);
            binder.bind(Database.class).in(com.google.inject.Scopes.SINGLETON);
            binder.bind(SecretResolver.class).in(com.google.inject.Scopes.SINGLETON);
            binder.bind(BaleiaCatalogStore.class).in(com.google.inject.Scopes.SINGLETON);
        });

        Injector injector = app
                .doNotInitializeLogging()
                .setRequiredConfigurationProperties(config)
                .initialize();

        return injector.getInstance(BaleiaCatalogStore.class);
    }
}
