package io.baleia.trino.catalogstore;

import com.google.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code @baleia-secret[<origin>:<key>]} with the actual value.
 * Three origin schemes are supported:
 * <ul>
 *   <li>{@code @baleia-secret[<catalog>:<key>]} — another row in the database (existing)</li>
 *   <li>{@code @baleia-secret[file:<name>]} — a file under {@code baleia.secret-file-base-dir} (new)</li>
 *   <li>{@code @baleia-secret[env:<VAR>]} — an environment variable (new)</li>
 * </ul>
 *
 * <p>The CREATE CATALOG text — which Trino logs and exposes in the Web UI — never
 * contains real credentials.
 *
 * <p><b>Why not {@code ${baleia-secret:...}}?</b> The Trino CLI (and several other
 * tools in the ecosystem — bash, picocli, Spring) interpret {@code ${...}} as a
 * variable expansion before the SQL reaches our {@code createCatalogProperties}. The
 * Trino <em>server</em> routes CREATE CATALOG
 * properties straight through, but any client that does its own {@code ${...}}
 * parsing will eat our placeholder. Using {@code @baleia-secret[...]} sidesteps
 * every standard expansion syntax.
 *
 * <p>The substitution is total and verified: after resolution, the resolver confirms no
 * output value still looks like a baleia-secret placeholder. This catches:
 * <ul>
 *   <li>Unresolved placeholders that shape-match {@code @baleia-secret[...]} literally
 *       (e.g. the catalog segment is uppercase and the regex never matched).</li>
 *   <li>A referenced secret whose stored value is itself a {@code @baleia-secret[...]}
 *       reference, i.e. a (deliberate or accidental) circular reference.</li>
 * </ul>
 * Both cases throw {@link IllegalStateException}, which {@code BaleiaCatalogStore} either
 * fails the catalog load (boot path) or surfaces back to the user (DDL path) with.
 */
public class SecretResolver
{
    private static final Pattern PLACEHOLDER =
            Pattern.compile("^@baleia-secret\\[([^\\]:]+):([^\\]]+)\\]$");

    private static final Pattern CATALOG_NAME =
            Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private static final Pattern FILE_NAME =
            Pattern.compile("^[A-Za-z0-9._-]+$");

    private static final Pattern ENV_NAME =
            Pattern.compile("^[A-Z_][A-Z0-9_]*$");

    private static final int FILE_MAX_SIZE = 64 * 1024;

    private final Database database;
    private final String secretFileBaseDir;

    @Inject
    public SecretResolver(Database database, BaleiaCatalogStoreConfig config)
    {
        this.database = database;
        this.secretFileBaseDir = config.getSecretFileBaseDir();
    }

    /**
     * Resolves all placeholders in the given properties map.
     *
     * <p>Each value is checked against the {@code @baleia-secret[...]} pattern.
     * Non-placeholder values pass through unchanged. The first group of the
     * match determines the origin scheme:
     * <ul>
     *   <li>{@code file:} — reads from a file under {@code baleia.secret-file-base-dir}</li>
     *   <li>{@code env:} — reads from a process environment variable</li>
     *   <li>anything else — treated as a catalog name, resolved via {@link Database}</li>
     * </ul>
     *
     * <p>After resolution, every output value is verified to not itself be a
     * placeholder, catching circular references and malformed inputs.
     *
     * @param properties the raw properties from the database or DDL
     * @return a new map with all placeholders resolved
     * @throws IllegalStateException if a placeholder cannot be resolved,
     *         a circular reference is detected, or a malformed placeholder is found
     */
    public Map<String, String> resolve(Map<String, String> properties)
    {
        Map<String, Optional<Map<String, String>>> memo = new HashMap<>();

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value == null) {
                throw new IllegalStateException("Property '" + key + "' has a null value");
            }

            Matcher m = PLACEHOLDER.matcher(value);
            if (m.matches()) {
                String origin = m.group(1);
                String identifier = m.group(2);

                String resolved = switch (origin) {
                    case "file" -> resolveFile(key, identifier);
                    case "env" -> resolveEnv(key, identifier);
                    default -> resolveCatalog(key, origin, identifier, memo);
                };

                if (PLACEHOLDER.matcher(resolved).matches()) {
                    throw new IllegalStateException(
                            "Resolved secret for '" + key + "' is itself a baleia-secret placeholder; "
                                    + "circular reference at " + origin + ":" + identifier);
                }
                out.put(key, resolved);
            }
            else {
                if (value.startsWith("@baleia-secret[")) {
                    throw new IllegalStateException(
                            "Property '" + key + "' value looks like a baleia-secret placeholder "
                                    + "but does not match the expected pattern: " + value);
                }
                out.put(key, value);
            }
        }
        return out;
    }

    /**
     * Resolves a {@code @baleia-secret[catalog:key]} reference from the database.
     *
     * @param key       the property key (for error messages)
     * @param catalog   the source catalog name
     * @param secretKey the key within the catalog's properties
     * @param memo      per-call memoization cache for catalog properties
     * @return the resolved value
     */
    private String resolveCatalog(String key, String catalog, String secretKey,
            Map<String, Optional<Map<String, String>>> memo)
    {
        if (!CATALOG_NAME.matcher(catalog).matches()) {
            throw new IllegalStateException(
                    "Property '" + key + "' has invalid origin '" + catalog
                            + "'; expected a catalog name, 'file', or 'env'");
        }

        return memo.computeIfAbsent(catalog, database::loadProperties)
                .map(props -> props.get(secretKey))
                .orElseThrow(() -> new IllegalStateException(
                        "Could not resolve secret for property '" + key
                                + "' (reference: " + catalog + ":" + secretKey + ")"));
    }

    /**
     * Resolves a {@code @baleia-secret[file:name]} reference from a file.
     *
     * <p>Reads the file on every call (no cross-call caching) to support
     * secret rotation without Trino restart. A trailing {@code \n} is stripped.
     *
     * @param key      the property key (for error messages)
     * @param fileName the file name (no path separators, no {@code ..})
     * @return the file content
     */
    private String resolveFile(String key, String fileName)
    {
        if (secretFileBaseDir.isEmpty()) {
            throw new IllegalStateException(
                    "Property '" + key + "' uses file: scheme but "
                            + "baleia.secret-file-base-dir is not configured");
        }

        if (!FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalStateException(
                    "Property '" + key + "' has invalid file name '" + fileName + "'");
        }

        Path basePath;
        try {
            basePath = Path.of(secretFileBaseDir).toAbsolutePath().normalize();
        }
        catch (InvalidPathException e) {
            throw new IllegalStateException(
                    "Property '" + key + "' has invalid secret-file-base-dir: " + secretFileBaseDir);
        }

        Path filePath;
        try {
            filePath = basePath.resolve(fileName).normalize();
        }
        catch (InvalidPathException e) {
            throw new IllegalStateException(
                    "Property '" + key + "' has invalid file path for '" + fileName + "'");
        }

        if (!filePath.startsWith(basePath)) {
            throw new IllegalStateException(
                    "Property '" + key + "' file path escapes the base directory: " + fileName);
        }

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new IllegalStateException(
                    "Property '" + key + "' references missing file: " + fileName);
        }

        if (Files.isSymbolicLink(filePath)) {
            try {
                Path realPath = filePath.toRealPath();
                if (!realPath.startsWith(basePath)) {
                    throw new IllegalStateException(
                            "Property '" + key + "' symbolic link points outside base directory: " + fileName);
                }
            }
            catch (IOException e) {
                throw new IllegalStateException(
                        "Property '" + key + "' cannot resolve symbolic link: " + fileName);
            }
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(filePath);
        }
        catch (IOException e) {
            throw new IllegalStateException(
                    "Property '" + key + "' cannot read file: " + fileName);
        }

        if (bytes.length > FILE_MAX_SIZE) {
            throw new IllegalStateException(
                    "Property '" + key + "' file exceeds 64 KiB limit: " + fileName);
        }

        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.endsWith("\n")) {
            content = content.substring(0, content.length() - 1);
        }

        return content;
    }

    private String resolveEnv(String key, String varName)
    {
        if (!ENV_NAME.matcher(varName).matches()) {
            throw new IllegalStateException(
                    "Property '" + key + "' has invalid environment variable name '" + varName + "'");
        }

        String value = System.getenv(varName);
        if (value == null) {
            throw new IllegalStateException(
                    "Property '" + key + "' references undefined environment variable: " + varName);
        }

        return value;
    }
}
