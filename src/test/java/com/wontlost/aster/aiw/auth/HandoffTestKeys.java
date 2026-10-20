package com.wontlost.aster.aiw.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 测试用 Ed25519 密钥对与按 cloud 格式签发的交接令牌（仅测试密钥，绝非真实机密）。 */
final class HandoffTestKeys {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    final KeyPair pair;

    HandoffTestKeys() {
        try {
            pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** X.509 DER 的 base64，与 AIW_HANDOFF_PUBLIC_KEY 同格式。 */
    String publicKeyB64() {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    /** 与 cloud signHandoffToken 字段顺序一致的完整声明。 */
    static Map<String, Object> claims(String jti, String locale, long iat) {
        var c = new LinkedHashMap<String, Object>();
        c.put("v", 1);
        c.put("aud", "ai-workspace");
        c.put("sub", "user-1");
        c.put("name", "Ann");
        c.put("email", "ann@example.com");
        c.put("locale", locale);
        c.put("role", "owner");
        c.put("iat", iat);
        c.put("exp", iat + 60);
        c.put("jti", jti);
        return c;
    }

    /** payload = base64url(JSON)，签名覆盖 payload 的 ASCII 字节。 */
    String sign(Map<String, Object> claims) {
        String payload = B64URL.encodeToString(MAPPER.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
        return payload + "." + B64URL.encodeToString(signBytes(payload));
    }

    private byte[] signBytes(String payload) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(pair.getPrivate());
            s.update(payload.getBytes(StandardCharsets.US_ASCII));
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
