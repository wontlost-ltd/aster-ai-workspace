package com.wontlost.aster.aiw.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 视图与交接控制器共用同一个重签地址构造（Task 11 合约）。 */
class HandoffUrlsTest {
    @Test
    void rehandoffEncodesNextAndNormalizesLocale() {
        assertEquals("https://c.test/api/ai-workspace/handoff?next=%2Fpanel%3FpolicyId%3Dp-1&locale=hi",
                HandoffUrls.rehandoff("https://c.test", "/panel?policyId=p-1", "hi"));
        assertEquals("https://c.test/api/ai-workspace/handoff?next=%2F&locale=en",
                HandoffUrls.rehandoff("https://c.test", "/evil?x", "fr"));
    }

    @Test
    void safeNextOnlyAllowsKnownRoutes() {
        assertEquals("/panel", HandoffUrls.safeNext("/panel"));
        assertEquals("/", HandoffUrls.safeNext("https://evil.test"));
        assertEquals("/", HandoffUrls.safeNext(null));
    }

    @Test
    void dashboardUsesCloudLocaleRoute() {
        assertEquals("https://aster-lang.cloud/zh/dashboard", HandoffUrls.dashboard("https://aster-lang.cloud", "zh"));
        assertEquals("https://aster-lang.cloud/hi/dashboard", HandoffUrls.dashboard("https://aster-lang.cloud/", "hi"));
        assertEquals("https://aster-lang.cloud/en/dashboard", HandoffUrls.dashboard("https://aster-lang.cloud", "fr"));
        assertEquals("https://aster-lang.cloud/en/dashboard", HandoffUrls.dashboard("https://aster-lang.cloud", null));
    }
}
