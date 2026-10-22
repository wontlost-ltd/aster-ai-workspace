package com.wontlost.aster.aiw.gdpr;

import com.wontlost.agui.AgUiJson;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * cloud「你的 AI 数据」的导出 / 清除入口（F23）。Spring MVC 控制器，不经 Vaadin，也没有会话或 CSRF：
 * 每次调用自带签名（{@link GdprRequestVerifier}），请求体按原始字符串参与签名摘要。
 *
 * <p>处理顺序：密钥未配置 → 503；签名 / 时间窗不过 → 401；请求体无效或与头部用户不一致 → 400；
 * nonce 已用 → 401；数据库不可用 → 503。拒绝日志只记原因码与路径，不记头、体、签名或用户 id。
 */
@RestController
public class GdprController {
    static final String EXPORT = "/internal/gdpr/export";
    static final String ERASE = "/internal/gdpr/erase";
    private static final Logger LOG = LoggerFactory.getLogger(GdprController.class);
    private static final int MAX_ID = 128;

    private final GdprRequestVerifier verifier;
    private final InternalNonceStore nonces;
    private final GdprService service;

    public GdprController(GdprRequestVerifier verifier, InternalNonceStore nonces, GdprService service) {
        this.verifier = verifier;
        this.nonces = nonces;
        this.service = service;
    }

    @PostMapping(path = EXPORT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> export(@RequestBody(required = false) String body, HttpServletRequest request) {
        return handle(EXPORT, body, request, service::export);
    }

    @PostMapping(path = ERASE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> erase(@RequestBody(required = false) String body, HttpServletRequest request) {
        return handle(ERASE, body, request, service::erase);
    }

    private ResponseEntity<String> handle(String path, String rawBody, HttpServletRequest request,
            Function<String, ObjectNode> action) {
        String body = rawBody == null ? "" : rawBody;
        if (!verifier.configured()) {
            return reject(path, HttpStatus.SERVICE_UNAVAILABLE, "gdpr_unconfigured");
        }
        Optional<GdprRequestVerifier.Verified> verified = verifier.verify("POST", path, request::getHeader, body);
        if (verified.isEmpty()) {
            boolean stale = verifier.stale(request.getHeader("X-Aster-Timestamp"));
            return reject(path, HttpStatus.UNAUTHORIZED, stale ? "stale_signature" : "bad_signature");
        }
        GdprRequestVerifier.Verified call = verified.get();
        Optional<String> userId = bodyUserId(body);
        if (userId.isEmpty()) {
            return reject(path, HttpStatus.BAD_REQUEST, "bad_request");
        }
        if (!userId.get().equals(call.userId())) {
            return reject(path, HttpStatus.BAD_REQUEST, "user_mismatch");
        }
        try {
            if (!nonces.claim(call.nonce(), call.nonceExpiresAt())) {
                return reject(path, HttpStatus.UNAUTHORIZED, "replayed_nonce");
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(AgUiJson.mapper().writeValueAsString(action.apply(call.userId())));
        } catch (DataAccessException down) {
            LOG.error("GDPR 调用失败：数据库不可用 (path={}): {}", path, down.getMessage());
            return reject(path, HttpStatus.SERVICE_UNAVAILABLE, "storage_unavailable");
        }
    }

    /** 请求体必须是含非空 userId（≤128 字符）的 JSON 对象；requestId 等其余字段忽略。 */
    static Optional<String> bodyUserId(String body) {
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

    private static ResponseEntity<String> reject(String path, HttpStatus status, String code) {
        LOG.warn("GDPR 调用被拒: reason={} path={}", code, path);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"" + code + "\"}");
    }
}
