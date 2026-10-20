package com.wontlost.aster.aiw.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ErrorTextsTest {

    private static final List<String> CODES = List.of("network", "session_expired", "source_too_large",
            "ai_quota_exhausted", "ai_email_unverified", "ai_rate_limited", "ai_banned", "byok_unavailable",
            "unauthorized", "provider_auth_failed", "provider_rejected_model", "provider_bad_request",
            "provider_rate_limited", "provider_unavailable", "out_of_scope", "cloud_misconfigured", "unknown");

    @Test
    void everyCodeExistsInAllFourLocales() {
        for (String locale : List.of("en", "zh", "de", "hi")) {
            for (String code : CODES) {
                String text = ErrorTexts.get(locale, code);
                assertNotNull(text, locale + ":" + code);
                assertFalse(text.isBlank(), locale + ":" + code);
            }
        }
    }

    @Test
    void localesAreActuallyTranslated() {
        assertEquals("请求过于频繁，请稍候再试。", ErrorTexts.get("zh", "ai_rate_limited"));
        assertTrue(ErrorTexts.get("hi", "network").chars().anyMatch(c -> c >= 0x0900 && c <= 0x097F),
                "hi 必须是天城文");
        assertTrue(ErrorTexts.get("de", "ai_quota_exhausted").startsWith("Ihr KI-Kontingent"));
    }

    @Test
    void unknownCodeIsNull() {
        assertNull(ErrorTexts.get("en", "no_such_code"));
        assertNull(ErrorTexts.get("en", null));
    }

    @Test
    void regionalOrUnknownLocalesFallBack() {
        assertEquals(ErrorTexts.get("zh", "network"), ErrorTexts.get("zh-CN", "network"));
        assertEquals(ErrorTexts.get("en", "network"), ErrorTexts.get("fr", "network"));
        assertEquals(ErrorTexts.get("en", "network"), ErrorTexts.get(null, "network"));
    }
}
