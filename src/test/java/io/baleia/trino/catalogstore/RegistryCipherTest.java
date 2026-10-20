package io.baleia.trino.catalogstore;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryCipherTest
{
    // Vector produced by the Go backend (internal/crypto.NewRegistryKeyring) with
    // GO_KEY and plaintext "s3cr3t". It pins the cross-language contract: HKDF
    // salt/info, key id and the envelope shape must match byte for byte.
    private static final String GO_KEY = "registry-key-0123456789abcdef0123456789";
    private static final String GO_ENVELOPE =
            "@baleia-enc[v1:4df53e73:qcEdPseBZDnCyYYVfyso8WVSPE4BpE63epScMTBZYBTVqw]";
    private static final Pattern KEY_ID = Pattern.compile("^@baleia-enc\\[v1:([0-9a-f]{8}):");

    @Test
    void decryptsGoBackendEnvelope()
    {
        RegistryCipher cipher = RegistryCipher.fromConfig(configWithKey(GO_KEY));
        assertEquals("s3cr3t", cipher.decrypt("token", GO_ENVELOPE));
    }

    @Test
    void roundTripsPlaintext()
    {
        RegistryCipher cipher = RegistryCipher.fromConfig(configWithKey(GO_KEY));
        String envelope = cipher.encrypt("a-real-secret");
        assertTrue(envelope.startsWith("@baleia-enc[v1:"));
        assertFalse(envelope.contains("a-real-secret"));
        assertEquals("a-real-secret", cipher.decrypt("k", envelope));
    }

    @Test
    void rotationReadsPreviousKeyAndEncryptsWithCurrent()
    {
        RegistryCipher old = RegistryCipher.fromConfig(configWithKey(GO_KEY));
        String oldEnvelope = old.encrypt("rotating");

        String newKey = "new-registry-key-0123456789abcdef012345";
        RegistryCipher rotated = RegistryCipher.fromConfig(configWithKeyAndPrevious(newKey, GO_KEY));

        // Dual-read: the old ciphertext still opens.
        assertEquals("rotating", rotated.decrypt("k", oldEnvelope));
        // New writes use the current key id.
        String newEnvelope = rotated.encrypt("rotating");
        assertNotEquals(keyId(oldEnvelope), keyId(newEnvelope));
        assertEquals("rotating", rotated.decrypt("k", newEnvelope));
    }

    @Test
    void unknownKeyFailsClosed()
    {
        RegistryCipher other = RegistryCipher.fromConfig(configWithKey("other-registry-key-0123456789abcdef0123"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> other.decrypt("k", GO_ENVELOPE));
        assertTrue(e.getMessage().contains("unknown registry key"));
        assertFalse(e.getMessage().contains("s3cr3t"));
    }

    @Test
    void disabledCipherRefusesToDecrypt()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RegistryCipher.disabled().decrypt("k", GO_ENVELOPE));
        assertTrue(e.getMessage().contains("baleia.registry-key is not configured"));
    }

    @Test
    void tamperedCiphertextFails()
    {
        RegistryCipher cipher = RegistryCipher.fromConfig(configWithKey(GO_KEY));
        String envelope = cipher.encrypt("value");
        // Flip the last payload character.
        char last = envelope.charAt(envelope.length() - 2);
        char swapped = last == 'A' ? 'B' : 'A';
        String tampered = envelope.substring(0, envelope.length() - 2) + swapped + "]";
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("k", tampered));
    }

    @Test
    void malformedEnvelopeIsClassifiedNotDecrypted()
    {
        assertFalse(RegistryCipher.isEnvelope("@baleia-enc[v1:bad]"));
        assertTrue(RegistryCipher.looksLikeEnvelope("@baleia-enc[v1:bad]"));
        assertFalse(RegistryCipher.looksLikeEnvelope("@baleia-secret[env:X]"));
    }

    private static String keyId(String envelope)
    {
        Matcher m = KEY_ID.matcher(envelope);
        assertTrue(m.find(), "envelope lacks a key id: " + envelope);
        return m.group(1);
    }

    private static BaleiaCatalogStoreConfig configWithKey(String key)
    {
        return baseConfig().setRegistryKey(key);
    }

    private static BaleiaCatalogStoreConfig configWithKeyAndPrevious(String key, String previous)
    {
        return baseConfig().setRegistryKey(key).setRegistryKeyPrevious(previous);
    }

    private static BaleiaCatalogStoreConfig baseConfig()
    {
        return new BaleiaCatalogStoreConfig()
                .setJdbcUrl("jdbc:postgresql://localhost:5432/_unused")
                .setUsername("u")
                .setPassword("p");
    }
}
