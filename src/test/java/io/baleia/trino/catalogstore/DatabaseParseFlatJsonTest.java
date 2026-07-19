package io.baleia.trino.catalogstore;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseParseFlatJsonTest
{
    @Test
    void parsesFlatObject()
    {
        Map<String, String> r = Database.parseFlatJson("{\"a\":\"1\",\"b\":\"2\"}");
        assertEquals("1", r.get("a"));
        assertEquals("2", r.get("b"));
        assertEquals(2, r.size());
    }

    @Test
    void preservesInsertionOrderLinkedHashMap()
    {
        Map<String, String> r = Database.parseFlatJson("{\"z\":\"1\",\"a\":\"2\"}");
        assertEquals("z", r.keySet().stream().findFirst().orElseThrow());
    }

    @Test
    void rejectsNonObject()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Database.parseFlatJson("[\"a\",\"b\"]"));
        assertTrue(e.getMessage().contains("not a JSON object"));
    }

    @Test
    void rejectsNonStringValues()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Database.parseFlatJson("{\"tpch.splits-per-node\":4}"));
        assertTrue(e.getMessage().contains("tpch.splits-per-node"));
    }

    @Test
    void rejectsInvalidJson()
    {
        assertThrows(IllegalArgumentException.class,
                () -> Database.parseFlatJson("{invalid json"));
    }
}