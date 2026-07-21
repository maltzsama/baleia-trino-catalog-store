package io.baleia.trino.catalogstore;

import com.google.inject.Inject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code @baleia-secret[<catalog>:<key>]} with the actual value from the database.
 * This ensures the CREATE CATALOG text — which Trino logs and exposes in the Web UI —
 * never contains real credentials.
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
            Pattern.compile("^@baleia-secret\\[([a-z][a-z0-9_]{0,62}):([^\\]]+)\\]$");

    private final Database database;

    @Inject
    public SecretResolver(Database database)
    {
        this.database = database;
    }

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
                String catalog = m.group(1);
                String secretKey = m.group(2);

                String resolved = memo.computeIfAbsent(catalog, database::loadProperties)
                        .map(props -> props.get(secretKey))
                        .orElseThrow(() -> new IllegalStateException(
                                "Could not resolve secret for property '" + key
                                        + "' (reference: " + catalog + ":" + secretKey + ")"));

                if (PLACEHOLDER.matcher(resolved).matches()) {
                    throw new IllegalStateException(
                            "Resolved secret for '" + key + "' is itself a baleia-secret placeholder; "
                                    + "circular reference at " + catalog + ":" + secretKey);
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
}