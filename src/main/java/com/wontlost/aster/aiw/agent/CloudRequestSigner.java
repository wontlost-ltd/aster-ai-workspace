package com.wontlost.aster.aiw.agent;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 服务 → cloud 的 HMAC 签名（ADR 0049 §2.2）。
 * <p>
 * canonical 与 cloud {@code canonicalAiWorkspace} 逐字一致：
 * {@code [METHOD, path, ts, nonce, sha256hex(body), userId].join("\n")}，
 * 以 HMAC-SHA256 小写 hex 输出。body 按 UTF-8 计算摘要，发送时必须用同一字符串的 UTF-8 字节。
 */
public final class CloudRequestSigner {

    static final String CALLER = "ai-workspace";
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String hmacKey;
    private final Clock clock;
    private final Supplier<String> nonceGen;

    public CloudRequestSigner(String hmacKey, Clock clock, Supplier<String> nonceGen) {
        this.hmacKey = hmacKey;
        this.clock = clock;
        this.nonceGen = nonceGen;
    }

    /** 16 字节随机数的小写 hex（32 字符），满足 cloud 的 ^[0-9a-f]{32}$。 */
    public static Supplier<String> randomNonce() {
        return () -> {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            return HEX.formatHex(bytes);
        };
    }

    /** 生成五个签名头；返回有序 Map 便于日志与测试。 */
    public Map<String, String> headers(String method, String path, String body, String userId) {
        String ts = Long.toString(clock.instant().getEpochSecond());
        String nonce = nonceGen.get();
        String canonical = canonical(method, path, ts, nonce, body, userId);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Internal-Caller", CALLER);
        headers.put("X-Aster-Timestamp", ts);
        headers.put("X-Aster-Nonce", nonce);
        headers.put("X-Aster-User-Id", userId);
        headers.put("X-Internal-Signature", hmacHex(hmacKey, canonical));
        return headers;
    }

    /**
     * 签名原文：{@code [METHOD, path, ts, nonce, sha256hex(body), userId].join("\n")}。
     * 两个方向（本服务 → cloud 的 LLM 调用、cloud → 本服务的 GDPR 调用）共用同一格式，只是密钥不同。
     */
    public static String canonical(String method, String path, String ts, String nonce, String body, String userId) {
        return String.join("\n", method.toUpperCase(Locale.ROOT), path, ts, nonce, sha256Hex(body), userId);
    }

    static String sha256Hex(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    /** Mac 非线程安全，每次签名新建一个；密钥未配置时在调用时报错而不是阻止启动。小写 hex。 */
    public static String hmacHex(String hmacKey, String canonical) {
        if (hmacKey == null || hmacKey.isBlank()) {
            throw new IllegalStateException("HMAC 密钥未配置，无法签名");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 签名失败", e);
        }
    }
}
