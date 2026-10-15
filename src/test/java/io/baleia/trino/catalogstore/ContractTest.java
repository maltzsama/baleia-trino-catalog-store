package io.baleia.trino.catalogstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the plugin's half of the contract shared with Baleia to the fixtures
 * in {@code contract/} (Baleia's contract tests read the same files from the
 * release they pin). Editing the code without the fixture, or the fixture
 * without the code, fails here.
 */
class ContractTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String resource(String name)
            throws IOException
    {
        try (InputStream in = ContractTest.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalStateException(name + " not found on the test classpath (contract/)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Object field(Class<?> owner, String name)
            throws ReflectiveOperationException
    {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    @Test
    void reservedNamesMatchContract()
            throws Exception
    {
        Set<String> want = new TreeSet<>();
        MAPPER.readTree(resource("reserved_catalog_names.json")).forEach(n -> want.add(n.asText()));
        @SuppressWarnings("unchecked")
        Set<String> got = new TreeSet<>((Set<String>) field(CatalogRow.class, "RESERVED"));
        assertEquals(want, got, "CatalogRow.RESERVED must equal contract/reserved_catalog_names.json");
    }

    @Test
    void placeholderCasesMatchContract()
            throws Exception
    {
        Pattern placeholder = (Pattern) field(SecretResolver.class, "PLACEHOLDER");
        JsonNode cases = MAPPER.readTree(resource("placeholder_cases.json"));
        for (JsonNode c : cases) {
            String value = c.get("value").asText();
            Matcher m = placeholder.matcher(value);
            if (c.get("matches").asBoolean()) {
                assertTrue(m.matches(), "should match: " + value);
                assertEquals(c.get("origin").asText(), m.group(1), value);
                assertEquals(c.get("key").asText(), m.group(2), value);
            }
            else {
                assertFalse(m.matches(), "should not match: " + value);
            }
        }
    }

    @Test
    void registryStatementsMatchContract()
            throws Exception
    {
        Map<String, String> want = new LinkedHashMap<>();
        String name = null;
        StringBuilder sql = new StringBuilder();
        for (String line : resource("registry_schema.sql").split("\n")) {
            if (line.startsWith("-- name: ")) {
                name = line.substring("-- name: ".length()).trim();
                sql.setLength(0);
            }
            else if (name != null && !line.startsWith("--") && !line.isBlank()) {
                sql.append(line).append(' ');
                if (line.trim().endsWith(";")) {
                    String stmt = sql.toString().trim();
                    want.put(name, normalize(stmt.substring(0, stmt.length() - 1)));
                    name = null;
                }
            }
        }
        assertFalse(want.isEmpty(), "registry_schema.sql has no statements");
        for (Map.Entry<String, String> e : want.entrySet()) {
            assertEquals(e.getValue(), normalize((String) field(Database.class, e.getKey())),
                    "Database." + e.getKey() + " must equal contract/registry_schema.sql");
        }
        for (Field f : Database.class.getDeclaredFields()) {
            if (f.getType() == String.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && ((String) field(Database.class, f.getName())).matches("(?is)^\\s*(SELECT|INSERT|UPDATE|DELETE)\\b.*trino_(catalog_registry|clusters).*")) {
                assertTrue(want.containsKey(f.getName()), "Database." + f.getName() + " is missing from contract/registry_schema.sql");
            }
        }
    }

    private static String normalize(String sql)
    {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
