package com.wontlost.aster.aiw.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CloudRequestSignerTest {

    private static final Clock FIXED = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);

    /** 与 cloud llm-caller.test.ts 共享的向量：两端 canonical 与 HMAC 必须逐字节一致。 */
    @Test
    void sharedVectorMatchesCloud() {
        var signer = new CloudRequestSigner("k".repeat(64), FIXED, () -> "a".repeat(32));
        Map<String, String> h = signer.headers("POST", "/api/llm/suggest", "{\"source\":\"x\"}", "u1");
        assertEquals("ai-workspace", h.get("X-Internal-Caller"));
        assertEquals("1800000000", h.get("X-Aster-Timestamp"));
        assertEquals("a".repeat(32), h.get("X-Aster-Nonce"));
        assertEquals("u1", h.get("X-Aster-User-Id"));
        assertEquals("82a00757510ea78239d0835a4f5343ba6e4b1fb02a5cfd563aeb686ec824aa0e",
                h.get("X-Internal-Signature"));
        assertEquals(5, h.size());
    }

    @Test
    void bodySha256MatchesSharedVector() {
        assertEquals("ee7bd4c48c9c43b1068696c254751f09083b15f0381f55e453051c40cb5a549c",
                CloudRequestSigner.sha256Hex("{\"source\":\"x\"}"));
    }

    @Test
    void methodIsUppercasedInCanonical() {
        var signer = new CloudRequestSigner("k".repeat(64), FIXED, () -> "a".repeat(32));
        String lower = signer.headers("post", "/api/llm/suggest", "{\"source\":\"x\"}", "u1").get("X-Internal-Signature");
        assertEquals("82a00757510ea78239d0835a4f5343ba6e4b1fb02a5cfd563aeb686ec824aa0e", lower);
    }

    @Test
    void signatureCoversUnicodeBodyAsUtf8() {
        var signer = new CloudRequestSigner("k".repeat(64), FIXED, () -> "a".repeat(32));
        String a = signer.headers("POST", "/p", "{\"q\":\"你好\"}", "u1").get("X-Internal-Signature");
        String b = signer.headers("POST", "/p", "{\"q\":\"你\"}", "u1").get("X-Internal-Signature");
        assertNotEquals(a, b);
    }

    /** cloud 只接受 ^[0-9a-f]{32}$ 的 nonce。 */
    @Test
    void randomNonceIs32LowercaseHexAndUnique() {
        var gen = CloudRequestSigner.randomNonce();
        List<String> nonces = List.of(gen.get(), gen.get(), gen.get());
        nonces.forEach(n -> assertTrue(n.matches("^[0-9a-f]{32}$"), n));
        assertEquals(3, nonces.stream().distinct().count());
    }
}
