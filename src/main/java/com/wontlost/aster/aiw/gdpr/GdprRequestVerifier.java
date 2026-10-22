package com.wontlost.aster.aiw.gdpr;

import com.wontlost.aster.aiw.agent.CloudRequestSigner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 校验 cloud → 本服务 GDPR 调用的签名（F23）。端点经公网可达（调用方是 Cloudflare Worker），签名是唯一防线。
 *
 * <p>签名原文与本服务调用 cloud 时一致（{@link CloudRequestSigner#canonical}），但用独立密钥
 * {@code aiw.gdpr-hmac-key}（AIW_GDPR_HMAC_KEY = cloud 的 AI_WORKSPACE_GDPR_HMAC_KEY），
 * 一个方向的密钥泄露不能伪造另一个方向的调用。分两步，读请求体之前先做不需要请求体的检查：
 * <ol>
 * <li>{@link #checkHeaders}：调用方、头部格式、±300 秒时间窗；</li>
 * <li>{@link #verifySignature}：对收到的原始请求体字节算摘要，常量时间比较签名。</li>
 * </ol>
 * nonce 的单次使用由调用方在请求体校验通过后登记（{@link InternalNonceStore}）。
 */
public final class GdprRequestVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(GdprRequestVerifier.class);
    static final String CALLER = "aster-cloud";
    static final long WINDOW_SECONDS = 300;
    /** nonce 登记在时间窗之外再保留的秒数，覆盖两端时钟偏差。 */
    static final long NONCE_GRACE_SECONDS = 60;
    /** 密钥至少 32 字节（HMAC-SHA256 的分组强度）。 */
    static final int MIN_KEY_BYTES = 32;
    private static final Pattern NONCE = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern TS = Pattern.compile("^\\d{1,12}$");
    private static final Pattern SIGNATURE = Pattern.compile("^[0-9a-fA-F]{64}$");

    /** 拒绝原因，即响应里的 {@code error} 码。 */
    enum Reason {
        BAD_SIGNATURE("bad_signature"), STALE_SIGNATURE("stale_signature");

        final String code;

        Reason(String code) {
            this.code = code;
        }
    }

    /** 头部检查通过后的调用要素：签名覆盖的用户 id、nonce 及其登记到期时间。 */
    record Headers(String ts, String nonce, String userId, String signature, Instant nonceExpiresAt) {}

    /** 头部检查结果：通过时 headers 非空，否则 reason 非空。 */
    record Check(Headers headers, Reason reason) {
        static Check ok(Headers headers) {
            return new Check(headers, null);
        }

        static Check rejected(Reason reason) {
            return new Check(null, reason);
        }
    }

    private final String key;
    private final Clock clock;

    /** @param key 已经过 {@link #effectiveKey} 处理的密钥；null 表示未配置 */
    GdprRequestVerifier(String key, Clock clock) {
        this.key = key;
        this.clock = clock;
    }

    boolean configured() {
        return key != null;
    }

    /**
     * 实际生效的密钥：去掉首尾空白（k8s Secret 常带结尾换行），为空、短于 {@value #MIN_KEY_BYTES} 字节、
     * 或与本服务调用 cloud 的 AIW_HMAC_KEY 相同时视为未配置（端点 503），并记错误日志说明原因。
     */
    static String effectiveKey(String gdprKey, String llmKey) {
        String k = gdprKey == null ? "" : gdprKey.strip();
        if (k.isEmpty()) {
            return null;
        }
        if (k.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
            LOG.error("AIW_GDPR_HMAC_KEY 短于 {} 字节，GDPR 端点停用", MIN_KEY_BYTES);
            return null;
        }
        if (llmKey != null && k.equals(llmKey.strip())) {
            LOG.error("AIW_GDPR_HMAC_KEY 不得与 AIW_HMAC_KEY 相同，GDPR 端点停用");
            return null;
        }
        return k;
    }

    /**
     * 不读请求体的检查。顺序：调用方与头部格式（bad_signature）→ 时间窗（stale_signature）；
     * 给出的是最先不满足的一条原因。
     *
     * @param header 按名取请求头（不区分大小写），缺失返回 null
     */
    Check checkHeaders(Function<String, String> header) {
        String ts = value(header, "X-Aster-Timestamp");
        String nonce = value(header, "X-Aster-Nonce");
        String userId = value(header, "X-Aster-User-Id");
        String signature = value(header, "X-Internal-Signature");
        if (!CALLER.equals(value(header, "X-Internal-Caller")) || userId.isEmpty()
                || !NONCE.matcher(nonce).matches() || !TS.matcher(ts).matches()
                || !SIGNATURE.matcher(signature).matches()) {
            return Check.rejected(Reason.BAD_SIGNATURE);
        }
        long seconds = Long.parseLong(ts);
        if (Math.abs(clock.instant().getEpochSecond() - seconds) > WINDOW_SECONDS) {
            return Check.rejected(Reason.STALE_SIGNATURE);
        }
        Instant expires = Instant.ofEpochSecond(seconds + WINDOW_SECONDS + NONCE_GRACE_SECONDS);
        return Check.ok(new Headers(ts, nonce, userId, signature.toLowerCase(Locale.ROOT), expires));
    }

    /** 对原始请求体字节校验签名（常量时间比较）。 */
    boolean verifySignature(String method, String path, Headers h, byte[] body) {
        String expected = CloudRequestSigner.hmacHex(key,
                CloudRequestSigner.canonical(method, path, h.ts(), h.nonce(), body, h.userId()));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                h.signature().getBytes(StandardCharsets.US_ASCII));
    }

    private static String value(Function<String, String> header, String name) {
        String v = header.apply(name);
        return v == null ? "" : v.strip();
    }
}
