package io.baleia.trino.catalogstore;

import io.baleia.trino.catalogstore.guard.BaleiaCatalogGuardFactory;
import io.trino.spi.Plugin;
import io.trino.spi.catalog.CatalogStoreFactory;
import io.trino.spi.security.SystemAccessControlFactory;

import java.util.List;

/**
 * Entry point for the Trino plugin loader.
 *
 * <p>Discovered via {@code META-INF/services/io.trino.spi.Plugin} (ServiceLoader).
 * Registers {@link BaleiaCatalogStoreFactory} under the name {@code "baleia"},
 * which is selected by {@code catalog.store=baleia} in {@code etc/config.properties}.
 *
 * @see BaleiaCatalogStoreFactory
 */
public class BaleiaCatalogStorePlugin
        implements Plugin
{
    @Override
    public Iterable<CatalogStoreFactory> getCatalogStoreFactories()
    {
        return List.of(new BaleiaCatalogStoreFactory());
    }

    @Override
    public Iterable<SystemAccessControlFactory> getSystemAccessControlFactories()
    {
        return List.of(new BaleiaCatalogGuardFactory());
    }
}