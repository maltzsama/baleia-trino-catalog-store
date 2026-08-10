package io.baleia.trino.catalogstore;

import io.trino.spi.catalog.CatalogStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BaleiaCatalogStoreFactoryTest
{
    @Test
    void getNameReturnsBaleia()
    {
        assertEquals("baleia", new BaleiaCatalogStoreFactory().getName());
    }

    @Test
    void createWiresConfigIntoBaleiaCatalogStore()
    {
        BaleiaCatalogStoreFactory factory = new BaleiaCatalogStoreFactory();
        CatalogStore store = factory.create(Map.of(
                "baleia.jdbc-url", "jdbc:postgresql://localhost:5432/baleia",
                "baleia.username", "u",
                "baleia.password", "p",
                "baleia.cluster-name", "prod",
                "baleia.max-connect-attempts", "7"));

        assertInstanceOf(BaleiaCatalogStore.class, store);
    }
}
