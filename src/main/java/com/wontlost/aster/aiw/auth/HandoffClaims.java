package com.wontlost.aster.aiw.auth;

/**
 * 交接令牌中经过验签的声明（cloud 的 HandoffClaims 去掉 v/aud/iat）。
 *
 * @param exp 过期时间（unix 秒）
 * @param jti 单次使用 id
 */
public record HandoffClaims(String sub, String name, String email, String locale, String role, long exp, String jti) {}
