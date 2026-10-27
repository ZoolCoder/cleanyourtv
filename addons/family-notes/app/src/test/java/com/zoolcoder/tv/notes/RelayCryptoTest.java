package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

public class RelayCryptoTest {
    private final SecureRandom random = new SecureRandom();

    @Test
    public void sealedTextOpensWithTheSameKeyAndBox() throws Exception {
        String key = RelayCrypto.newKey(random), box = RelayCrypto.newBoxId(random);
        String wire = RelayCrypto.seal(key, box, "سلام · hello", random);
        assertEquals("سلام · hello", RelayCrypto.open(key, box, wire));
        assertTrue(RelayClient.WIRE.matcher(wire).matches());
    }

    @Test
    public void eachSealUsesAFreshIv() throws Exception {
        String key = RelayCrypto.newKey(random), box = RelayCrypto.newBoxId(random);
        assertNotEquals(RelayCrypto.seal(key, box, "same", random), RelayCrypto.seal(key, box, "same", random));
    }

    @Test
    public void anotherBoxOrKeyOrAChangedByteDoesNotOpen() throws Exception {
        String key = RelayCrypto.newKey(random), box = RelayCrypto.newBoxId(random);
        String wire = RelayCrypto.seal(key, box, "secret", random);
        refused(key, RelayCrypto.newBoxId(random), wire);
        refused(RelayCrypto.newKey(random), box, wire);
        char last = wire.charAt(wire.length() - 2);
        refused(key, box, wire.substring(0, wire.length() - 2) + (last == 'A' ? 'B' : 'A') + wire.charAt(wire.length() - 1));
        refused(key, box, wire.substring(0, 30));
    }

    @Test
    public void onlyStrictBase64UrlIsRead() {
        String[] bad = {null, "", "ab+/", "abc=", "a", "ab cd", "ab\ncd"};
        for (String text : bad) {
            try {
                byte[] bytes = RelayCrypto.decode(text);
                if (text != null && text.isEmpty() && bytes.length == 0) continue;
                fail("decoded " + text);
            } catch (GeneralSecurityException expected) {
            }
        }
    }

    @Test
    public void keysTokensAndIdsHaveTheRelaysLengths() {
        assertTrue(RelayClient.BOX_ID.matcher(RelayCrypto.newBoxId(random)).matches());
        assertTrue(RelayClient.TOKEN.matcher(RelayCrypto.newToken(random)).matches());
        assertEquals(43, RelayCrypto.newKey(random).length());
    }

    @Test
    public void sha256HexMatchesTheStandardVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", RelayCrypto.sha256Hex("abc"));
        assertTrue(RelayClient.HASH.matcher(RelayCrypto.sha256Hex(RelayCrypto.newToken(random))).matches());
    }

    /** Opens the message the relay's send page sealed (relay/test/vector.json, written by npm run vector). */
    @Test
    public void opensWhatTheSendPageSeals() throws Exception {
        File vector = find("relay/test/vector.json");
        assumeTrue("relay/test/vector.json not in this checkout", vector != null);
        JSONObject v = new JSONObject(new String(Files.readAllBytes(vector.toPath()), StandardCharsets.UTF_8));
        assertEquals(v.getString("plaintext"), RelayCrypto.open(v.getString("key"), v.getString("boxId"), v.getString("wire")));
        refused(v.getString("key"), RelayCrypto.newBoxId(random), v.getString("wire"));
    }

    private static File find(String relative) {
        for (File dir = new File(System.getProperty("user.dir")).getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    private static void refused(String key, String box, String wire) {
        try {
            RelayCrypto.open(key, box, wire);
            fail("opened");
        } catch (GeneralSecurityException expected) {
        }
    }
}
