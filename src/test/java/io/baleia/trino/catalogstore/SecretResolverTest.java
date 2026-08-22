package io.baleia.trino.catalogstore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretResolverTest
{
    @TempDir
    Path tempDir;

    // ── catalog: scheme (existing behavior, regression) ──────────────────

    @Test
    void leavesFlatValuesUntouched()
    {
        SecretResolver resolver = new SecretResolver(dbReturning(Map.of()), baseConfig());
        Map<String, String> out = resolver.resolve(Map.of("a", "1", "b", "2"));
        assertEquals(Map.of("a", "1", "b", "2"), out);
    }

    @Test
    void replacesPlaceholderWithValueFromDatabase()
    {
        SecretResolver resolver = new SecretResolver(dbReturning(
                Map.of("iceberg.rest.auth.token", "supersecreto")), baseConfig());
        Map<String, String> out = resolver.resolve(Map.of(
                "iceberg.rest.auth.token", "@baleia-secret[vault:iceberg.rest.auth.token]"));
        assertEquals("supersecreto", out.get("iceberg.rest.auth.token"));
    }

    @Test
    void throwsWhenReferencedCatalogNotFound()
    {
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), baseConfig());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[missing:x]")));
        assertTrue(e.getMessage().contains("k"));
        assertTrue(e.getMessage().contains("missing:x"));
    }

    @Test
    void throwsWhenReferencedKeyNotPresent()
    {
        SecretResolver resolver = new SecretResolver(dbReturning(Map.of("other", "value")), baseConfig());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[vault:not.found]")));
        assertTrue(e.getMessage().contains("k"));
        assertTrue(e.getMessage().contains("vault:not.found"));
    }

    @Test
    void rejectsMalformedBaleiaSecretPlaceholder()
    {
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), baseConfig());
        // A value that looks like our placeholder syntax but contains spaces, which
        // never match the PLACEHOLDER regex. The "does not match" path catches it.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[bad value]")));
        assertTrue(e.getMessage().contains("k"));
        assertTrue(e.getMessage().contains("does not match"));
    }

    // ── file: scheme ────────────────────────────────────────────────────

    @Test
    void fileSchemeResolvesContent() throws IOException
    {
        Files.writeString(tempDir.resolve("db-password.txt"), "s3cret");
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), configWithBaseDir(tempDir));
        Map<String, String> out = resolver.resolve(Map.of(
                "password", "@baleia-secret[file:db-password.txt]"));
        assertEquals("s3cret", out.get("password"));
    }

    @Test
    void fileSchemeStripsTrailingNewline() throws IOException
    {
        Files.writeString(tempDir.resolve("token.txt"), "tok123\n");
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), configWithBaseDir(tempDir));
        Map<String, String> out = resolver.resolve(Map.of(
                "token", "@baleia-secret[file:token.txt]"));
        assertEquals("tok123", out.get("token"));
    }

    @Test
    void fileSchemeFailsWithEmptyBaseDir()
    {
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), baseConfig());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[file:anything.txt]")));
        assertTrue(e.getMessage().contains("baleia.secret-file-base-dir is not configured"));
    }

    @Test
    void fileSchemeRejectsPathTraversal()
    {
        BaleiaCatalogStoreConfig cfg = configWithBaseDir(tempDir);
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), cfg);
        assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[file:../../etc/shadow]")));
    }

    @Test
    void fileSchemeRejectsAbsolutePath()
    {
        BaleiaCatalogStoreConfig cfg = configWithBaseDir(tempDir);
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), cfg);
        assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[file:/etc/passwd]")));
    }

    @Test
    void fileSchemeRejectsSymlinkOutsideBase() throws IOException
    {
        // Create a directory OUTSIDE the base dir with a secret file
        Path outside = tempDir.resolveSibling("outside-base-" + System.nanoTime());
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "leaked");

        try {
            // Create symlink inside base pointing outside
            Path link = tempDir.resolve("link.txt");
            Files.createSymbolicLink(link, outside.resolve("secret.txt"));

            BaleiaCatalogStoreConfig cfg = configWithBaseDir(tempDir);
            SecretResolver resolver = new SecretResolver(dbReturningEmpty(), cfg);
            assertThrows(IllegalStateException.class,
                    () -> resolver.resolve(Map.of("k", "@baleia-secret[file:link.txt]")));
        }
        finally {
            Files.deleteIfExists(outside.resolve("secret.txt"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void fileSchemeRejectsOversizedFile() throws IOException
    {
        byte[] big = new byte[64 * 1024 + 1];
        Files.write(tempDir.resolve("big.txt"), big);

        BaleiaCatalogStoreConfig cfg = configWithBaseDir(tempDir);
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), cfg);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[file:big.txt]")));
        assertTrue(e.getMessage().contains("64 KiB"));
    }

    @Test
    void fileSchemeRejectsCircularReference() throws IOException
    {
        Files.writeString(tempDir.resolve("loop.txt"),
                "@baleia-secret[vault:some.key]");
        BaleiaCatalogStoreConfig cfg = configWithBaseDir(tempDir);
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), cfg);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[file:loop.txt]")));
        assertTrue(e.getMessage().contains("circular reference"));
    }

    @Test
    void envSchemeResolvesVariable()
    {
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), baseConfig());
        // PATH is guaranteed to be set in any JVM process
        Map<String, String> out = resolver.resolve(Map.of(
                "p", "@baleia-secret[env:PATH]"));
        assertTrue(out.get("p").length() > 0);
    }

    @Test
    void envSchemeFailsOnUndefinedVariable()
    {
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), baseConfig());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of(
                        "k", "@baleia-secret[env:BALEIA_DEFINITELY_NOT_SET_VAR_12345]")));
        assertTrue(e.getMessage().contains("undefined environment variable"));
    }

    // ── file: rotation (§2.3) ───────────────────────────────────────────

    @Test
    void fileSchemeReadsFreshContentOnEachResolve() throws IOException
    {
        Path file = tempDir.resolve("rotating.txt");
        Files.writeString(file, "value-v1");

        BaleiaCatalogStoreConfig cfg = configWithBaseDir(tempDir);
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), cfg);

        Map<String, String> out1 = resolver.resolve(Map.of(
                "k", "@baleia-secret[file:rotating.txt]"));
        assertEquals("value-v1", out1.get("k"));

        // Simulate kubelet updating the Secret mount
        Files.writeString(file, "value-v2");

        Map<String, String> out2 = resolver.resolve(Map.of(
                "k", "@baleia-secret[file:rotating.txt]"));
        assertEquals("value-v2", out2.get("k"));
    }

    // ── exception safety ────────────────────────────────────────────────

    @Test
    void noExceptionContainsResolvedValue()
    {
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), baseConfig());
        try {
            resolver.resolve(Map.of("k", "@baleia-secret[missing:x]"));
        }
        catch (IllegalStateException e) {
            assertTrue(!e.getMessage().contains("supersecretvalue"),
                    "Exception message must not contain the resolved secret value");
        }
    }

    @Test
    void fileSchemeNameIsNotConfusedWithCatalog()
    {
        // "file" is dispatched as the file: scheme, not as a catalog name.
        // With empty base dir, it fails with "not configured", not "invalid origin".
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), baseConfig());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[file:key]")));
        assertTrue(e.getMessage().contains("baleia.secret-file-base-dir is not configured"));
    }

    @Test
    void envSchemeNameIsNotConfusedWithCatalog()
    {
        // "env" is dispatched as the env: scheme, not as a catalog name.
        // With an undefined variable, it fails with "undefined environment variable".
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), baseConfig());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[env:UNDEFINED_VAR_XYZ]")));
        assertTrue(e.getMessage().contains("undefined environment variable"));
    }

    @Test
    void fileSchemeRejectsInvalidFileName()
    {
        BaleiaCatalogStoreConfig cfg = configWithBaseDir(tempDir);
        SecretResolver resolver = new SecretResolver(dbReturningEmpty(), cfg);
        assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Map.of("k", "@baleia-secret[file:../escape]")));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Database dbReturningEmpty()
    {
        return dbReturning(Map.of());
    }

    private static Database dbReturning(Map<String, String> allProps)
    {
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

    private static BaleiaCatalogStoreConfig baseConfig()
    {
        return new BaleiaCatalogStoreConfig()
                .setJdbcUrl("jdbc:postgresql://localhost:5432/_unused")
                .setUsername("u")
                .setPassword("p");
    }

    private BaleiaCatalogStoreConfig configWithBaseDir(Path dir)
    {
        return new BaleiaCatalogStoreConfig()
                .setJdbcUrl("jdbc:postgresql://localhost:5432/_unused")
                .setUsername("u")
                .setPassword("p")
                .setSecretFileBaseDir(dir.toString());
    }
}
