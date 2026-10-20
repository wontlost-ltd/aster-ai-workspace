package com.wontlost.aster.aiw.auth;

import java.util.Optional;

/** 校验浏览器随每次 run 带来的短期票据；无效、过期或伪造一律返回空。 */
public interface TicketVerifier {

    Optional<TicketUser> verify(String ticket);
}
