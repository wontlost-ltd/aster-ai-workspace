package com.wontlost.aster.aiw.auth;

import java.io.Serializable;

/**
 * 交接成功后存入 HTTP 会话的当前用户；会话可能被序列化，因此实现 Serializable。
 *
 * @param userId cloud 用户 id（同时作为租户 id）
 * @param name   显示名，可为 null
 * @param email  邮箱，可为 null
 * @param locale 界面语言短码（en / zh / de / hi）
 * @param role   cloud 角色
 */
public record AiwUser(String userId, String name, String email, String locale, String role)
        implements Serializable {}
