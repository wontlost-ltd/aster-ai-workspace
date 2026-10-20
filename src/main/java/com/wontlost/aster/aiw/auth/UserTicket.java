package com.wontlost.aster.aiw.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * run 票据：页面把它放进 AG-UI forwardedProps.ticket，AsterAgent 据此识别用户。
 *
 * <p>格式 {@code base64url("userId|locale|exp") + "." + hex(HMAC-SHA256(key, payload 段))}，有效期 12 小时。
 * 密钥为空时：verify 一律返回空，issue 抛 IllegalStateException（只记一次错误日志）。
 * verify 对任何畸形、篡改、过期输入都返回空，绝不抛异常（AsterAgent 把异常视为服务端配置错误）。
 */
public class UserTicket implements TicketVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(UserTicket.class);
    static final Duration TTL = Duration.ofHours(12);
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;
    private final Clock clock;
    private final AtomicBoolean warned = new AtomicBoolean();

    public UserTicket(String ticketKey, Clock clock) {
        this.key = ticketKey == null || ticketKey.isBlank() ? null : ticketKey.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** 为会话用户签发票据。 */
    public String issue(AiwUser user) {
        if (key == null) {
            warnOnce();
            throw new IllegalStateException("AIW_TICKET_KEY 未配置，无法签发 run 票据");
        }
        long exp = clock.instant().plus(TTL).getEpochSecond();
        String raw = user.userId() + "|" + user.locale() + "|" + exp;
        String payload = B64URL.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return payload + "." + hmacHex(payload);
    }

    @Override
    public Optional<TicketUser> verify(String ticket) {
        if (key == null) {
            warnOnce();
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(parse(ticket));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private TicketUser parse(String ticket) {
        int dot = ticket == null ? -1 : ticket.indexOf('.');
        if (dot <= 0 || dot != ticket.lastIndexOf('.')) {
            return null;
        }
        String payload = ticket.substring(0, dot);
        byte[] given = ticket.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(hmacHex(payload).getBytes(StandardCharsets.US_ASCII), given)) {
            return null;
        }
        return unpack(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    /** 从右往左拆，userId 中即使含 '|' 也不会错位。 */
    private TicketUser unpack(String raw) {
        int expSep = raw.lastIndexOf('|');
        int localeSep = raw.lastIndexOf('|', expSep - 1);
        if (localeSep <= 0) {
            return null;
        }
        long exp = Long.parseLong(raw.substring(expSep + 1));
        if (clock.instant().getEpochSecond() > exp) {
            return null;
        }
        return new TicketUser(raw.substring(0, localeSep), raw.substring(localeSep + 1, expSep));
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    private void warnOnce() {
        if (warned.compareAndSet(false, true)) {
            LOG.error("AIW_TICKET_KEY 未配置：run 票据既不能签发也不能校验，所有对话请求都会被拒绝");
        }
    }
}
