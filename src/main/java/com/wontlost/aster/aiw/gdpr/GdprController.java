package com.wontlost.aster.aiw.gdpr;

import com.wontlost.agui.AgUiJson;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * cloud「你的 AI 数据」的导出 / 清除入口（F23）。Spring MVC 控制器，不经 Vaadin，也没有会话或 CSRF：
 * 每次调用自带签名（{@link GdprRequestVerifier}）。
 *
 * <p>端点经公网可达，读请求体之前先做廉价检查，请求体按原始字节读取且有上限：
 * <ol>
 * <li>密钥未配置 → 503 {@code gdpr_unconfigured}；</li>
 * <li>调用方 / 头部格式 → 401 {@code bad_signature}；时间窗 → 401 {@code stale_signature}；</li>
 * <li>声明的 Content-Length 超过 {@value #MAX_BODY} 字节 → 413 {@code body_too_large}（不读请求体）；
 *     未声明长度（chunked）时最多读 {@value #MAX_BODY}+1 字节，超出同样 413；</li>
 * <li>对读到的原始字节校验签名 → 401 {@code bad_signature}；</li>
 * <li>请求体不是含 userId 的 JSON 对象 → 400 {@code bad_request}；与头部用户不一致 → 400 {@code user_mismatch}；</li>
 * <li>nonce 已用 → 401 {@code replayed_nonce}；数据库不可用 → 503 {@code storage_unavailable}。</li>
 * </ol>
 * 拒绝只记 DEBUG 日志与计数 {@code aiw.gdpr.rejected{reason}}（未认证的洪水不刷屏），不记头、体、签名或用户 id。
 */
@RestController
public class GdprController {
    static final String EXPORT = "/internal/gdpr/export";
    static final String ERASE = "/internal/gdpr/erase";
    /** 请求体上限：合法请求体只有 userId 与 requestId，远小于此。 */
    static final int MAX_BODY = 4096;
    private static final Logger LOG = LoggerFactory.getLogger(GdprController.class);
    private static final int MAX_ID = 128;

    private final GdprRequestVerifier verifier;
    private final InternalNonceStore nonces;
    private final GdprService service;
    private final MeterRegistry meters;

    public GdprController(GdprRequestVerifier verifier, InternalNonceStore nonces, GdprService service,
            MeterRegistry meters) {
        this.verifier = verifier;
        this.nonces = nonces;
        this.service = service;
        this.meters = meters;
    }

    @PostMapping(path = EXPORT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> export(HttpServletRequest request) {
        return handle(EXPORT, request, service::export);
    }

    @PostMapping(path = ERASE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> erase(HttpServletRequest request) {
        return handle(ERASE, request, service::erase);
    }

    private ResponseEntity<String> handle(String path, HttpServletRequest request, Function<String, ObjectNode> action) {
        if (!verifier.configured()) {
            return reject(HttpStatus.SERVICE_UNAVAILABLE, "gdpr_unconfigured");
        }
        GdprRequestVerifier.Check check = verifier.checkHeaders(request::getHeader);
        if (check.reason() != null) {
            return reject(HttpStatus.UNAUTHORIZED, check.reason().code);
        }
        if (request.getContentLengthLong() > MAX_BODY) {
            return reject(HttpStatus.PAYLOAD_TOO_LARGE, "body_too_large");
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY + 1);
        } catch (IOException e) {
            return reject(HttpStatus.BAD_REQUEST, "bad_request");
        }
        if (body.length > MAX_BODY) {
            return reject(HttpStatus.PAYLOAD_TOO_LARGE, "body_too_large");
        }
        GdprRequestVerifier.Headers h = check.headers();
        if (!verifier.verifySignature("POST", path, h, body)) {
            return reject(HttpStatus.UNAUTHORIZED, "bad_signature");
        }
        Optional<String> userId = bodyUserId(body);
        if (userId.isEmpty()) {
            return reject(HttpStatus.BAD_REQUEST, "bad_request");
        }
        if (!userId.get().equals(h.userId())) {
            return reject(HttpStatus.BAD_REQUEST, "user_mismatch");
        }
        try {
            if (!nonces.claim(h.nonce(), h.nonceExpiresAt())) {
                return reject(HttpStatus.UNAUTHORIZED, "replayed_nonce");
            }
            ObjectNode result = action.apply(h.userId());
            meters.counter("aiw.gdpr.calls", "path", path).increment();
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(AgUiJson.mapper().writeValueAsString(result));
        } catch (DataAccessException down) {
            LOG.error("GDPR 调用失败：数据库不可用 (path={}): {}", path, down.getMessage());
            return reject(HttpStatus.SERVICE_UNAVAILABLE, "storage_unavailable");
        }
    }

    /** 请求体必须是含非空 userId（≤128 字符）的 UTF-8 JSON 对象；requestId 等其余字段忽略。 */
    static Optional<String> bodyUserId(byte[] body) {
        try {
            JsonNode node = AgUiJson.mapper().readTree(body);
            if (node == null || !node.isObject() || !node.path("userId").isString()) {
                return Optional.empty();
            }
            String id = node.get("userId").asString();
            return id.isBlank() || id.length() > MAX_ID ? Optional.empty() : Optional.of(id);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private ResponseEntity<String> reject(HttpStatus status, String code) {
        meters.counter("aiw.gdpr.rejected", "reason", code).increment();
        LOG.debug("GDPR 调用被拒: reason={}", code);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"" + code + "\"}");
    }
}
