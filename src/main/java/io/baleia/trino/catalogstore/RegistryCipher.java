package io.baleia.trino.catalogstore;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Envelope encryption of catalog-registry values (Phase B of baleia#31 / D4).
 *
 * <p>Baleia seals catalog-origin secrets at rest as
 * {@code @baleia-enc[v1:<key-id>:<base64(nonce|ciphertext)>]}. This class
 * decrypts those values at boot and on {@code CREATE CATALOG}, and seals the
 * one value the plugin persists itself (a catalog's reference to its own row).
 *
 * <p>The key is <b>dedicated</b> ({@code baleia.registry-key}); it is never
 * derived from a JWT secret. The derivation matches the Go backend exactly:
 * <pre>
 *   HKDF-SHA256(ikm = key, salt = 32 zero bytes, info = "baleia/registry/v1", 32)
 *   key-id = first 8 hex chars of SHA-256(derived key)
 * </pre>
 * AES-256-GCM uses a 12-byte nonce prefixed to the ciphertext and no AAD.
 *
 * <p>{@code baleia.registry-key-previous} enables a rotation: both keys decrypt,
 * the current one encrypts. An absent key keeps Phase A (plaintext rows).
 */
public class RegistryCipher
{
    private static final Pattern ENVELOPE =
            Pattern.compile("^@baleia-enc\\[(v[0-9]+):([0-9a-f]{8}):([A-Za-z0-9+/]+)\\]$");

    private static final String ENVELOPE_V1 = "v1";
    private static final byte[] HKDF_INFO = "baleia/registry/v1".getBytes(StandardCharsets.UTF_8);
    private static final int KEY_LEN = 32;
    private static final int MIN_KEY_CHARS = 32;
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKeySpec> keys;
    private final String currentKeyId;
    private final SecureRandom random = new SecureRandom();

    private RegistryCipher(Map<String, SecretKeySpec> keys, String currentKeyId)
    {
        this.keys = Map.copyOf(keys);
        this.currentKeyId = currentKeyId;
    }

    /** Builds the cipher from config; an empty key disables Phase B. */
    public static RegistryCipher fromConfig(BaleiaCatalogStoreConfig config)
    {
        String current = config.getRegistryKey();
        String previous = config.getRegistryKeyPrevious();
        if (current == null || current.isEmpty()) {
            return disabled();
        }
        if (current.length() < MIN_KEY_CHARS) {
            throw new IllegalArgumentException("baleia.registry-key must be at least " + MIN_KEY_CHARS + " characters");
        }
        Map<String, SecretKeySpec> derived = new LinkedHashMap<>();
        String currentId = derive(derived, current);
        if (previous != null && !previous.isEmpty()) {
            if (previous.length() < MIN_KEY_CHARS) {
                throw new IllegalArgumentException("baleia.registry-key-previous must be at least " + MIN_KEY_CHARS + " characters");
            }
            derive(derived, previous);
        }
        return new RegistryCipher(derived, currentId);
    }

    public static RegistryCipher disabled()
    {
        return new RegistryCipher(Map.of(), null);
    }

    public boolean isEnabled()
    {
        return currentKeyId != null;
    }

    /** Whether {@code value} is a well-formed {@code @baleia-enc[...]} envelope. */
    public static boolean isEnvelope(String value)
    {
        return value != null && ENVELOPE.matcher(value).matches();
    }

    /** Whether {@code value} starts like an envelope but is malformed. */
    public static boolean looksLikeEnvelope(String value)
    {
        return value != null && value.startsWith("@baleia-enc[");
    }

    /**
     * Decrypts one envelope. Error messages name the property and key id, never
     * the plaintext or ciphertext.
     */
    public String decrypt(String property, String envelope)
    {
        if (!isEnabled()) {
            throw new IllegalStateException("Property '" + property
                    + "' is sealed but baleia.registry-key is not configured");
        }
        Matcher m = ENVELOPE.matcher(envelope);
        if (!m.matches()) {
            throw new IllegalStateException("Property '" + property
                    + "' value looks like a baleia-enc envelope but does not match the expected pattern");
        }
        if (!ENVELOPE_V1.equals(m.group(1))) {
            throw new IllegalStateException("Property '" + property
                    + "' uses unsupported registry envelope version '" + m.group(1) + "'");
        }
        SecretKeySpec key = keys.get(m.group(2));
        if (key == null) {
            throw new IllegalStateException("Property '" + property
                    + "' is sealed with an unknown registry key (" + m.group(2) + ")");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(m.group(3));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalStateException("Property '" + property + "' has an invalid registry envelope", e);
        }
        if (raw.length < NONCE_LEN + TAG_BITS / 8) {
            throw new IllegalStateException("Property '" + property + "' has a truncated registry envelope");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, NONCE_LEN));
            byte[] plaintext = cipher.doFinal(raw, NONCE_LEN, raw.length - NONCE_LEN);
            return new String(plaintext, StandardCharsets.UTF_8);
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException("Property '" + property
                    + "' could not be decrypted (wrong key, corrupt envelope, or tampered ciphertext)", e);
        }
    }

    /** Seals a plaintext with the current key. */
    public String encrypt(String plaintext)
    {
        if (!isEnabled()) {
            throw new IllegalStateException("baleia.registry-key is not configured");
        }
        SecretKeySpec key = keys.get(currentKeyId);
        byte[] nonce = new byte[NONCE_LEN];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, sealed, 0, nonce.length);
            System.arraycopy(ciphertext, 0, sealed, nonce.length, ciphertext.length);
            return "@baleia-enc[" + ENVELOPE_V1 + ":" + currentKeyId + ":"
                    + Base64.getEncoder().withoutPadding().encodeToString(sealed) + "]";
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not seal a registry value", e);
        }
    }

    private static String derive(Map<String, SecretKeySpec> out, String secret)
    {
        byte[] raw = hkdfSha256(secret.getBytes(StandardCharsets.UTF_8), new byte[32], HKDF_INFO, KEY_LEN);
        String id = HexFormat.of().formatHex(sha256(raw)).substring(0, 8);
        out.put(id, new SecretKeySpec(raw, "AES"));
        return id;
    }

    /** RFC 5869 HKDF-SHA256; matches Go's {@code hkdf.Key(sha256.New, ...)}. */
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length)
    {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] okm = new byte[length];
            byte[] block = new byte[0];
            int pos = 0;
            for (int counter = 1; pos < length; counter++) {
                mac.reset();
                mac.update(block);
                mac.update(info);
                mac.update((byte) counter);
                block = mac.doFinal();
                int take = Math.min(block.length, length - pos);
                System.arraycopy(block, 0, okm, pos, take);
                pos += take;
            }
            return okm;
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF-SHA256 is unavailable", e);
        }
    }

    private static byte[] sha256(byte[] value)
    {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
