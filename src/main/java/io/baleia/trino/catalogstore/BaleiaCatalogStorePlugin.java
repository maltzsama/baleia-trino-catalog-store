package io.baleia.trino.catalogstore;

import io.trino.spi.Plugin;
import io.trino.spi.catalog.CatalogStoreFactory;

import java.util.List;

public class BaleiaCatalogStorePlugin
        implements Plugin
{
    @Override
    public Iterable<CatalogStoreFactory> getCatalogStoreFactories()
    {
        return List.of(new BaleiaCatalogStoreFactory());
    }
}