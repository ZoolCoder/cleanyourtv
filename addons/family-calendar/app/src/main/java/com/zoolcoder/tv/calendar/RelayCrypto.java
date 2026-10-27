package com.zoolcoder.tv.calendar;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The Family away crypto, the same on the TV and on the relay's send page: AES-256-GCM with a 12-byte random
 * IV and the UTF-8 bytes of the box id as associated data, so a message cannot be moved to another box. Wire
 * form: base64url(iv || ciphertext || tag), no padding. Ids, tokens and keys are random bytes in base64url.
 * Shared with Family Calendar: keep this file free of anything notes-specific.
 */
final class RelayCrypto {
    static final int BOX_ID_BYTES = 16, TOKEN_BYTES = 32, KEY_BYTES = 32, IV_BYTES = 12, TAG_BITS = 128;
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]*");

    private RelayCrypto() {
    }

    static String newBoxId(SecureRandom random) {
        return randomText(random, BOX_ID_BYTES);
    }

    static String newToken(SecureRandom random) {
        return randomText(random, TOKEN_BYTES);
    }

    static String newKey(SecureRandom random) {
        return randomText(random, KEY_BYTES);
    }

    private static String randomText(SecureRandom random, int bytes) {
        byte[] out = new byte[bytes];
        random.nextBytes(out);
        return encode(out);
    }

    /** What the relay stores in place of a token: SHA-256 of its UTF-8 text, in lowercase hex. */
    static String sha256Hex(String text) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }

    static String seal(String key, String boxId, String plaintext, SecureRandom random) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, boxId, iv);
        byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return encode(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
    }

    /** The plaintext; throws when the wire text is malformed, was made with another key or box, or was altered. */
    static String open(String key, String boxId, String wire) throws GeneralSecurityException {
        byte[] bytes = decode(wire);
        if (bytes.length < IV_BYTES + TAG_BITS / 8) throw new GeneralSecurityException("too short");
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(bytes, 0, iv, 0, IV_BYTES);
        Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, boxId, iv);
        byte[] plain = cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain)).toString();
        } catch (CharacterCodingException e) {
            throw new GeneralSecurityException("not UTF-8");
        }
    }

    private static Cipher cipher(int mode, String key, String boxId, byte[] iv) throws GeneralSecurityException {
        byte[] raw = decode(key);
        if (raw.length != KEY_BYTES) throw new GeneralSecurityException("bad key");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(raw, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(boxId.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Strict base64url without padding: anything else, standard base64 included, is refused. */
    static byte[] decode(String text) throws GeneralSecurityException {
        if (text == null || !BASE64URL.matcher(text).matches() || text.length() % 4 == 1) {
            throw new GeneralSecurityException("not base64url");
        }
        try {
            return Base64.getUrlDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("not base64url");
        }
    }
}
