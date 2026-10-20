package com.wontlost.aster.aiw.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 交接相关地址（Task 11 合约）：交接控制器与视图的登录跳转共用，保证两处规则一致。
 */
public final class HandoffUrls {
    /** 与 cloud 允许的 next 完全一致；不匹配时回落到根路径，杜绝开放重定向。 */
    private static final Pattern NEXT = Pattern.compile("^/(panel(\\?policyId=[A-Za-z0-9_-]{1,64})?)?$");

    private HandoffUrls() {}

    public static String safeNext(String next) {
        return next != null && NEXT.matcher(next).matches() ? next : "/";
    }

    /** cloud 侧重新签发交接令牌的地址；next 先过白名单，locale 归一为四种界面语言之一。 */
    public static String rehandoff(String cloudUrl, String next, String locale) {
        return cloudUrl + "/api/ai-workspace/handoff?next="
                + URLEncoder.encode(safeNext(next), StandardCharsets.UTF_8)
                + "&locale=" + Locales.normalize(locale);
    }
}
