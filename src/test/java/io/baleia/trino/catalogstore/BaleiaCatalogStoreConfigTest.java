package io.baleia.trino.catalogstore;

import io.airlift.units.Duration;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaleiaCatalogStoreConfigTest
{
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp()
    {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown()
    {
        factory.close();
    }

    @Test
    void defaultsAreSane()
    {
        BaleiaCatalogStoreConfig cfg = new BaleiaCatalogStoreConfig();
        assertEquals("default", cfg.getClusterName());
        assertEquals(5, cfg.getMaxConnectAttempts());
        assertEquals(2000, cfg.getInitialBackoff().toMillis());
        assertEquals(30000, cfg.getMaxBackoff().toMillis());
    }

    @Test
    void maxConnectAttemptsRejectsOutOfRange()
    {
        BaleiaCatalogStoreConfig cfg = baseConfig().setMaxConnectAttempts(0);
        assertFalse(validator.validate(cfg).isEmpty(), "0 attempts must fail validation");
    }

    @Test
    void maxConnectAttemptsAcceptsInRange()
    {
        BaleiaCatalogStoreConfig cfg = baseConfig().setMaxConnectAttempts(10);
        assertTrue(validator.validate(cfg).isEmpty(), "10 attempts must pass validation");
    }

    @Test
    void rejectsInitialBackoffGreaterThanMaxBackoff()
    {
        BaleiaCatalogStoreConfig cfg = baseConfig()
                .setInitialBackoff(new Duration(60, SECONDS))
                .setMaxBackoff(new Duration(30, SECONDS));
        assertFalse(validator.validate(cfg).isEmpty(), "initial > max backoff must fail validation");
    }

    @Test
    void acceptsInitialBackoffEqualToOne()
    {
        BaleiaCatalogStoreConfig cfg = baseConfig()
                .setInitialBackoff(new Duration(30, SECONDS))
                .setMaxBackoff(new Duration(30, SECONDS));
        assertTrue(validator.validate(cfg).isEmpty(), "initial == max backoff is valid");
    }

    private static BaleiaCatalogStoreConfig baseConfig()
    {
        return new BaleiaCatalogStoreConfig()
                .setJdbcUrl("jdbc:postgresql://localhost:5432/baleia")
                .setUsername("u")
                .setPassword("p");
    }
}
