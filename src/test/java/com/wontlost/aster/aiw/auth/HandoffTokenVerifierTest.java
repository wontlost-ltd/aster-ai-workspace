package com.wontlost.aster.aiw.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HandoffTokenVerifierTest {
    private static final long NOW = 1_800_000_000L;

    /**
     * 跨语言 fixture：公钥与令牌由 cloud 的 signHandoffToken（Node WebCrypto）以 now=4000000000 生成。
     * 仅测试密钥。
     */
    static final String FIXTURE_PUBLIC_KEY = "MCowBQYDK2VwAyEAoji1rY+fMVJkmbg1UHk1JUSBWDRcMe1A4q0yS6WjBzE=";

    static final String FIXTURE_TOKEN =
            "eyJ2IjoxLCJhdWQiOiJhaS13b3Jrc3BhY2UiLCJzdWIiOiJ1c2VyLWZpeHR1cmUtMSIsIm5hbWUiOiLlvKDkuIkgWmhhbmciLCJlbWFpbCI6InpoYW5nQGV4YW1wbGUuY29tIiwibG9jYWxlIjoiemgiLCJyb2xlIjoib3duZXIiLCJpYXQiOjQwMDAwMDAwMDAsImV4cCI6NDAwMDAwMDA2MCwianRpIjoiM2QzOTUzODMtY2YyNS00MmU0LWEyN2UtNzYzZjBiMmM3YzkyIn0"
                    + ".XbYP7cLPoZYDla_Da-ESoAEmZl0S-1_SyTYFtEgbyWOp50TNQwSdF84aGE2vBOo0NI85atRWZGluwsaySJIDBg";

    private final HandoffTestKeys keys = new HandoffTestKeys();

    private static Clock at(long epochSecond) {
        return Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
    }

    private HandoffTokenVerifier verifier(long now) {
        return new HandoffTokenVerifier(keys.publicKeyB64(), at(now));
    }

    @Test
    void validTokenYieldsClaims() {
        String token = keys.sign(HandoffTestKeys.claims("j-1", "zh", NOW));
        var claims = verifier(NOW + 10).verify(token).orElseThrow();
        assertEquals(new HandoffClaims("user-1", "Ann", "ann@example.com", "zh", "owner", NOW + 60, "j-1"), claims);
    }

    @Test
    void nullNameAndEmailAreAllowed() {
        Map<String, Object> c = HandoffTestKeys.claims("j-2", "en", NOW);
        c.put("name", null);
        c.put("email", null);
        var claims = verifier(NOW).verify(keys.sign(c)).orElseThrow();
        assertEquals(null, claims.name());
        assertEquals(null, claims.email());
    }

    @Test
    void tamperedPayloadIsRejected() {
        String token = keys.sign(HandoffTestKeys.claims("j-1", "zh", NOW));
        String other = keys.sign(HandoffTestKeys.claims("j-1", "en", NOW));
        String forged = other.substring(0, other.indexOf('.')) + token.substring(token.indexOf('.'));
        assertTrue(verifier(NOW).verify(forged).isEmpty());
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() {
        String token = new HandoffTestKeys().sign(HandoffTestKeys.claims("j-1", "zh", NOW));
        assertTrue(verifier(NOW).verify(token).isEmpty());
    }

    @Test
    void wrongAudienceIsRejected() {
        Map<String, Object> c = HandoffTestKeys.claims("j-1", "zh", NOW);
        c.put("aud", "other-service");
        assertTrue(verifier(NOW).verify(keys.sign(c)).isEmpty());
    }

    @Test
    void wrongVersionIsRejected() {
        Map<String, Object> c = HandoffTestKeys.claims("j-1", "zh", NOW);
        c.put("v", 2);
        assertTrue(verifier(NOW).verify(keys.sign(c)).isEmpty());
    }

    @Test
    void expiredTokenIsRejectedButFiveSecondSkewIsAllowed() {
        String token = keys.sign(HandoffTestKeys.claims("j-1", "zh", NOW));
        assertTrue(verifier(NOW + 65).verify(token).isPresent());
        assertTrue(verifier(NOW + 66).verify(token).isEmpty());
    }

    @Test
    void tokenIssuedFarInTheFutureIsRejected() {
        String token = keys.sign(HandoffTestKeys.claims("j-1", "zh", NOW + 120));
        assertTrue(verifier(NOW).verify(token).isEmpty());
    }

    @Test
    void malformedInputNeverThrows() {
        var v = verifier(NOW);
        for (String bad : new String[] {null, "", ".", "abc", "a.b.c", "!!!.???", "e30.AAAA"}) {
            assertTrue(v.verify(bad).isEmpty(), "应拒绝: " + bad);
        }
    }

    @Test
    void invalidPublicKeyRejectsEverythingWithoutThrowing() {
        var v = new HandoffTokenVerifier("not-a-key", at(NOW));
        assertTrue(v.verify(keys.sign(HandoffTestKeys.claims("j-1", "zh", NOW))).isEmpty());
        assertTrue(new HandoffTokenVerifier("", at(NOW)).verify("a.b").isEmpty());
    }

    /** cloud 端真实签名器产出的令牌必须在 Java 端验签通过（字节级互通）。 */
    @Test
    void cloudSignedFixtureVerifies() {
        var v = new HandoffTokenVerifier(FIXTURE_PUBLIC_KEY, at(4_000_000_030L));
        var claims = v.verify(FIXTURE_TOKEN).orElseThrow();
        assertEquals(new HandoffClaims("user-fixture-1", "张三 Zhang", "zhang@example.com", "zh", "owner",
                4_000_000_060L, "3d395383-cf25-42e4-a27e-763f0b2c7c92"), claims);
    }
}
