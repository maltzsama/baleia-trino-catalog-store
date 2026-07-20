package io.baleia.trino.catalogstore;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretResolverTest
{
    @Test
    void leavesFlatValuesUntouched()
    {
        SecretResolver resolver = new SecretResolver(dbReturning(Map.of()));
        Map<String, String> out = resolver.resolve(Map.of("a", "1", "b", "2"));
        assertEquals(Map.of("a", "1", "b", "2"), out);
    }

    @Test
    void replacesPlaceholderWithValueFromDatabase()
    {
        SecretResolver resolver = new SecretResolver(dbReturning(
                Map.of("iceberg.rest.auth.token", "supersecreto")));
        Map<String, String> out = resolver.resolve(Map.of(
                "iceberg.rest.auth.token", "@baleia-secret[vault:iceberg.rest.auth.token]"));
        assertEquals("supersecreto", out.get("iceberg.rest.auth.token"));
    }

    @Test
    void throwsWhenReferencedCatalogNotFound()
    {
        SecretResolver resolver = new SecretResolver(dbReturningEmpty());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[missing:x]")));
        assertTrue(e.getMessage().contains("k"));
        assertTrue(e.getMessage().contains("missing:x"));
    }

    @Test
    void throwsWhenReferencedKeyNotPresent()
    {
        SecretResolver resolver = new SecretResolver(dbReturning(Map.of("other", "value")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[vault:not.found]")));
        assertTrue(e.getMessage().contains("k"));
        assertTrue(e.getMessage().contains("vault:not.found"));
    }

    @Test
    void rejectsMalformedBaleiaSecretPlaceholder()
    {
        SecretResolver resolver = new SecretResolver(dbReturningEmpty());
        // D4b: a value that looks like our placeholder syntax but doesn't match the strict
        // regex (uppercase catalog segment) is rejected with an explicit error, not silently
        // returned. Returning it would risk leaking the placeholder into logs as if it were
        // a resolved credential.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[MiXed:chave]")));
        assertTrue(e.getMessage().contains("k"));
        assertTrue(e.getMessage().contains("does not match"));
    }

    // helpers -----------------------------------------------------------------

    /** A Database that ignores JDBC and returns a fixed Optional per catalog name. */
    private static Database dbReturningEmpty()
    {
        return dbReturning(Map.of());
    }

    private static Database dbReturning(Map<String, String> allProps)
    {
        // PGSimpleDataSource rejects a null JDBC URL, so we hand it a placeholder URL.
        // The stub overrides loadProperties, so the URL is never used.
        BaleiaCatalogStoreConfig cfg = new BaleiaCatalogStoreConfig()
                .setJdbcUrl("jdbc:postgresql://localhost:5432/_unused")
                .setUsername("u")
                .setPassword("p");
        return new Database(cfg)
        {
            @Override
            public Optional<Map<String, String>> loadProperties(String catalogName)
            {
                return Optional.ofNullable(allProps.isEmpty() ? null : allProps);
            }
        };
    }
}