package com.wontlost.aster.aiw.auth;

import com.wontlost.aster.aiw.config.AiwProperties;
import com.wontlost.aster.aiw.web.ColorSchemePreference;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * cloud 交接入口（ADR 0049 §2.6）：cloud 签发 60 秒一次性令牌并 302 到 {@code /handoff?token=&next=}。
 *
 * <p>令牌有效、未过期且 jti 首次使用：把用户写入会话、更换会话 id（防会话固定），302 到白名单内的 next。
 * 其余情况（缺失、签名错、过期、重放）一律 302 回 cloud 的交接地址重新签发（Review Focus #1）。
 * 可选的 {@code theme=dark|light} 记进会话，next 白名单不含它，交接后的页面由此沿用亮 / 暗（F29）。
 */
@RestController
public class HandoffController {
    private static final Logger LOG = LoggerFactory.getLogger(HandoffController.class);
    private final HandoffTokenVerifier verifier;
    private final HandoffJtiStore jtis;
    private final AiwProperties props;

    public HandoffController(HandoffTokenVerifier verifier, HandoffJtiStore jtis, AiwProperties props) {
        this.verifier = verifier;
        this.jtis = jtis;
        this.props = props;
    }

    @GetMapping("/handoff")
    public ResponseEntity<Void> handoff(
            @RequestParam(name = "token", required = false) String token,
            @RequestParam(name = "next", required = false) String next,
            @RequestParam(name = ColorSchemePreference.PARAM, required = false) String theme,
            HttpServletRequest request) {
        String target = HandoffUrls.safeNext(next);
        Optional<HandoffClaims> claims = verifier.authentic(token);
        if (claims.isEmpty()) {
            return redirect(rehandoff(target, "en"));
        }
        HandoffClaims c = claims.get();
        String locale = Locales.normalize(c.locale());
        String rejected = !verifier.fresh(c) ? "expired" : !jtis.claim(c.jti(), expiry(c)) ? "replayed" : null;
        if (rejected != null) {
            // 只记原因码，不记令牌、jti 或用户信息
            LOG.warn("交接令牌被拒: reason={}", rejected);
            return redirect(rehandoff(target, locale));
        }
        HttpSession session = sessionFor(request, c.sub());
        SessionUser.set(session, new AiwUser(c.sub(), c.name(), c.email(), locale, c.role()));
        ColorSchemePreference.remembered(theme)
                .ifPresent(value -> session.setAttribute(ColorSchemePreference.ATTRIBUTE, value));
        request.changeSessionId();
        return redirect(target);
    }

    /**
     * 会话里已是另一位用户（同一浏览器换了 cloud 账号）→ 作废旧会话再新建，旧用户的 VaadinSession 与会话历史
     * 不会带给新用户（只带过亮 / 暗偏好，它属于浏览器而非用户）；同一用户或匿名会话沿用（随后更换会话 id 防会话固定）。
     */
    private static HttpSession sessionFor(HttpServletRequest request, String userId) {
        HttpSession existing = request.getSession(false);
        if (existing != null && existing.getAttribute(SessionUser.ATTRIBUTE) instanceof AiwUser u
                && !u.userId().equals(userId)) {
            Object scheme = existing.getAttribute(ColorSchemePreference.ATTRIBUTE);
            existing.invalidate();
            HttpSession fresh = request.getSession(true);
            if (scheme != null) {
                fresh.setAttribute(ColorSchemePreference.ATTRIBUTE, scheme);
            }
            return fresh;
        }
        return request.getSession(true);
    }

    /** jti 登记保留到令牌连宽限期也失效为止，此后清理不影响防重放。 */
    private static Instant expiry(HandoffClaims c) {
        return Instant.ofEpochSecond(c.exp() + HandoffTokenVerifier.SKEW_SECONDS);
    }

    private String rehandoff(String next, String locale) {
        return HandoffUrls.rehandoff(props.cloudUrl(), next, locale);
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
}
