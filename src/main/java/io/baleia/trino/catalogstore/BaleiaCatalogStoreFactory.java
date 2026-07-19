package io.baleia.trino.catalogstore;

import com.google.inject.Injector;
import io.airlift.bootstrap.Bootstrap;
import io.trino.spi.catalog.CatalogStore;
import io.trino.spi.catalog.CatalogStoreFactory;

import java.util.Map;

import static io.airlift.configuration.ConfigBinder.configBinder;

public class BaleiaCatalogStoreFactory
        implements CatalogStoreFactory
{
    @Override
    public String getName()
    {
        return "baleia";
    }

    @Override
    public CatalogStore create(Map<String, String> config)
    {
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