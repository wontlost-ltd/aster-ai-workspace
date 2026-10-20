package com.wontlost.aster.aiw.auth;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 校验 cloud 签发的交接令牌（ADR 0049 §2.6）。
 *
 * <p>令牌格式与 cloud {@code signHandoffToken} 一致：{@code base64url(JSON 声明) + "." + base64url(Ed25519 签名)}，
 * 签名覆盖 payload 段的 ASCII 字节。任何畸形、伪造、过期输入都返回空，绝不抛异常。
 * 单次使用（jti）不在这里判断，由 {@link HandoffJtiStore} 负责。
 */
public class HandoffTokenVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(HandoffTokenVerifier.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** 允许的时钟偏差（秒）：exp 之后 5 秒内仍接受。 */
    static final long SKEW_SECONDS = 5;
    /** iat 晚于当前时间超过该值（秒）视为伪造或时钟严重错乱。 */
    private static final long MAX_FUTURE_IAT_SECONDS = 60;
    private static final int MAX_JTI_LENGTH = 64;

    private final PublicKey key;
    private final Clock clock;

    /**
     * @param publicKeyB64 Ed25519 公钥（base64 X.509 DER）；非法时记错误日志，此后所有令牌都被拒绝
     */
    public HandoffTokenVerifier(String publicKeyB64, Clock clock) {
        this.key = parseKey(publicKeyB64);
        this.clock = clock;
    }

    private static PublicKey parseKey(String b64) {
        try {
            byte[] der = Base64.getDecoder().decode(b64 == null ? "" : b64.trim());
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            LOG.error("AIW_HANDOFF_PUBLIC_KEY 缺失或不是合法的 Ed25519 X.509 公钥，所有交接令牌都将被拒绝");
            return null;
        }
    }

    /** 验签 + 版本 + 受众 + 时效，全部通过才返回声明。 */
    public Optional<HandoffClaims> verify(String token) {
        return authentic(token).filter(this::fresh);
    }

    /**
     * 只验签名、版本、受众与 iat 合理性，不看是否过期。
     * 控制器在令牌过期时仍需可信的 locale 来回跳 cloud 重签。
     */
    public Optional<HandoffClaims> authentic(String token) {
        try {
            return Optional.ofNullable(parse(token));
        } catch (RuntimeException e) {
            return Optional.ofNullable(reject("bad_format"));
        }
    }

    /**
     * 拒绝原因记一行 WARN 便于排查（如 T16 密钥对不匹配导致的重定向循环）；
     * 只记原因码，绝不记令牌、声明或用户信息。
     */
    private static HandoffClaims reject(String reason) {
        LOG.warn("交接令牌被拒: reason={}", reason);
        return null;
    }

    /** exp 之后再宽限 {@link #SKEW_SECONDS} 秒。 */
    public boolean fresh(HandoffClaims claims) {
        return clock.instant().getEpochSecond() <= claims.exp() + SKEW_SECONDS;
    }

    private HandoffClaims parse(String token) {
        if (key == null) {
            return reject("no_public_key");
        }
        if (token == null) {
            return reject("missing");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return reject("bad_format");
        }
        String payload = token.substring(0, dot);
        if (!signatureValid(payload, Base64.getUrlDecoder().decode(token.substring(dot + 1)))) {
            return reject("bad_signature");
        }
        JsonNode json = MAPPER.readTree(Base64.getUrlDecoder().decode(payload));
        if (!"ai-workspace".equals(json.path("aud").asString(""))) {
            return reject("bad_audience");
        }
        return accepted(json) ? toClaims(json) : reject("bad_claims");
    }

    private boolean signatureValid(String payload, byte[] signature) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(payload.getBytes(StandardCharsets.US_ASCII));
            return s.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /** 已验签的声明还要满足：v=1、aud=ai-workspace、sub/jti 合法、iat 不在遥远的未来。 */
    private boolean accepted(JsonNode json) {
        long now = clock.instant().getEpochSecond();
        String sub = json.path("sub").asString("");
        String jti = json.path("jti").asString("");
        return json.path("v").asInt(0) == 1
                && "ai-workspace".equals(json.path("aud").asString(""))
                && !sub.isBlank()
                && !jti.isBlank()
                && jti.length() <= MAX_JTI_LENGTH
                && json.path("exp").isIntegralNumber()
                && json.path("iat").asLong(Long.MAX_VALUE) <= now + MAX_FUTURE_IAT_SECONDS;
    }

    private static HandoffClaims toClaims(JsonNode json) {
        return new HandoffClaims(
                json.path("sub").asString(),
                nullableText(json, "name"),
                nullableText(json, "email"),
                json.path("locale").asString("en"),
                json.path("role").asString(""),
                json.path("exp").asLong(),
                json.path("jti").asString());
    }

    private static String nullableText(JsonNode json, String field) {
        JsonNode node = json.path(field);
        return node.isString() ? node.asString() : null;
    }
}
