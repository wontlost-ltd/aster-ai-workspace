package com.wontlost.aster.aiw.auth;

/**
 * 短期票据解出的用户身份。
 *
 * @param userId cloud 用户 id（同时作为租户 id）
 * @param locale 界面语言短码（en / zh / de / hi），决定错误文案语言与默认 CNL 语言
 */
public record TicketUser(String userId, String locale) {}
