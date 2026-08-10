package io.baleia.trino.catalogstore;

import io.airlift.units.Duration;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest
{
    @Test
    void ceilSecondsRoundsUp()
    {
        assertEquals(1, Database.ceilSeconds(new Duration(1, MILLISECONDS)));
        assertEquals(2, Database.ceilSeconds(new Duration(1500, MILLISECONDS)));
        assertEquals(10, Database.ceilSeconds(new Duration(10, SECONDS)));
        // 999_999ms -> 1000s (round up, not truncate to 999)
        assertEquals(1000, Database.ceilSeconds(new Duration(999_999, MILLISECONDS)));
    }

    @Test
    void ceilSecondsClampsToMinimumOfOne()
    {
        // A zero/negative timeout means "no timeout" in the driver — the opposite
        // of what the user asked for, so clamp to 1s.
        assertEquals(1, Database.ceilSeconds(new Duration(0, MILLISECONDS)));
    }

    @Test
    void truncateKeepsShortStrings()
    {
        assertEquals("abc", Database.truncate("abc", 10));
        assertEquals(null, Database.truncate(null, 10));
    }

    @Test
    void truncateCutsLongStringsAtLimit()
    {
        assertEquals("a".repeat(1000), Database.truncate("a".repeat(5000), 1000));
    }

    @Test
    void isRetryableClassifiesConnectionAndTransientStates()
    {
        assertTrue(Database.isRetryable(sqlex("08006")));   // connection_failure
        assertTrue(Database.isRetryable(sqlex("08P01")));   // protocol_violation (class 08)
        assertTrue(Database.isRetryable(sqlex("57P03")));   // cannot_connect_now
        assertTrue(Database.isRetryable(sqlex("40001")));   // serialization_failure
        assertTrue(Database.isRetryable(sqlex("40P01")));   // deadlock_detected
        assertTrue(Database.isRetryable(sqlex(null)));      // unclassified -> retry
    }

    @Test
    void isRetryableRejectsPermanentErrors()
    {
        assertFalse(Database.isRetryable(sqlex("23505")));  // unique_violation
        assertFalse(Database.isRetryable(sqlex("42601")));  // syntax_error
        assertFalse(Database.isRetryable(sqlex("42P01")));  // undefined_table
    }

    private static SQLException sqlex(String state)
    {
        return new SQLException("boom", state);
    }
}
