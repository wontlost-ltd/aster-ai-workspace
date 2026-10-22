package com.wontlost.aster.aiw.gdpr;

import com.wontlost.aster.aiw.agent.CloudRequestSigner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 校验 cloud → 本服务 GDPR 调用的签名（F23）。端点经公网可达（调用方是 Cloudflare Worker），签名是唯一防线。
 *
 * <p>签名原文与本服务调用 cloud 时一致（{@link CloudRequestSigner#canonical}），但用独立密钥
 * {@code aiw.gdpr-hmac-key}（AIW_GDPR_HMAC_KEY = cloud 的 AI_WORKSPACE_GDPR_HMAC_KEY），
 * 一个方向的密钥泄露不能伪造另一个方向的调用。
 * 检查顺序：密钥已配置 → 调用方与头部格式 → ±300 秒时间窗 → 常量时间比较签名。
 * nonce 的单次使用由调用方在请求体校验通过后登记（{@link InternalNonceStore}）。
 */
public final class GdprRequestVerifier {
    static final String CALLER = "aster-cloud";
    static final long WINDOW_SECONDS = 300;
    /** nonce 登记在时间窗之外再保留的秒数，覆盖两端时钟偏差。 */
    static final long NONCE_GRACE_SECONDS = 60;
    private static final Pattern NONCE = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern TS = Pattern.compile("^\\d{1,12}$");
    private static final Pattern SIGNATURE = Pattern.compile("^[0-9a-fA-F]{64}$");

    /** 校验通过的调用：签名覆盖的用户 id 与 nonce 及其登记到期时间。 */
    record Verified(String userId, String nonce, Instant nonceExpiresAt) {}

    private final String key;
    private final Clock clock;

    GdprRequestVerifier(String key, Clock clock) {
        this.key = key;
        this.clock = clock;
    }

    boolean configured() {
        return key != null && !key.isBlank();
    }

    /**
     * @param header 按名取请求头（不区分大小写），缺失返回 null
     * @return 通过时为 {@link Verified}；否则为空（调用方统一回 401 bad_signature / stale_signature 见 {@link #stale}）
     */
    Optional<Verified> verify(String method, String path, Function<String, String> header, String body) {
        String ts = value(header, "X-Aster-Timestamp");
        String nonce = value(header, "X-Aster-Nonce");
        String userId = value(header, "X-Aster-User-Id");
        String signature = value(header, "X-Internal-Signature");
        if (!configured() || !CALLER.equals(value(header, "X-Internal-Caller")) || userId.isEmpty()
                || !NONCE.matcher(nonce).matches() || !TS.matcher(ts).matches()
                || !SIGNATURE.matcher(signature).matches() || stale(ts)) {
            return Optional.empty();
        }
        String expected = CloudRequestSigner.hmacHex(key, CloudRequestSigner.canonical(method, path, ts, nonce, body, userId));
        boolean ok = MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        if (!ok) {
            return Optional.empty();
        }
        Instant expires = Instant.ofEpochSecond(Long.parseLong(ts) + WINDOW_SECONDS + NONCE_GRACE_SECONDS);
        return Optional.of(new Verified(userId, nonce, expires));
    }

    /** 时间戳格式合法但在 ±300 秒窗口外（用于给出更具体的拒绝原因）。 */
    boolean stale(String ts) {
        if (ts == null || !TS.matcher(ts).matches()) {
            return false;
        }
        return Math.abs(clock.instant().getEpochSecond() - Long.parseLong(ts)) > WINDOW_SECONDS;
    }

    private static String value(Function<String, String> header, String name) {
        String v = header.apply(name);
        return v == null ? "" : v.strip();
    }
}
