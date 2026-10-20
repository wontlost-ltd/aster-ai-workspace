package com.wontlost.aster.aiw.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 面向用户的错误文案（en / zh / de / hi），键为错误码。
 * <p>
 * 配额、封禁、provider 等文案沿用 aster-cloud 现有 {@code ai.error*} 翻译，保持两边措辞一致。
 * 未知错误码返回 null，由调用方回落到 cloud 给出的原文。
 */
public final class ErrorTexts {

    private static final String DEFAULT_LOCALE = "en";
    private static final List<String> LOCALES = List.of("en", "zh", "de", "hi");
    private static final Map<String, Properties> BUNDLES = LOCALES.stream()
            .collect(Collectors.toUnmodifiableMap(Function.identity(), ErrorTexts::load));

    private ErrorTexts() {
    }

    /** 按界面语言取文案；地区变体（zh-CN）取语言部分，不支持的语言回落英文。 */
    public static String get(String uiLocale, String code) {
        if (code == null) {
            return null;
        }
        return BUNDLES.get(language(uiLocale)).getProperty(code);
    }

    private static String language(String uiLocale) {
        if (uiLocale == null) {
            return DEFAULT_LOCALE;
        }
        String lang = uiLocale.split("[-_]", 2)[0].toLowerCase();
        return LOCALES.contains(lang) ? lang : DEFAULT_LOCALE;
    }

    private static Properties load(String locale) {
        String path = "/i18n/errors_" + locale + ".properties";
        try (InputStream in = ErrorTexts.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺少错误文案资源 " + path);
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return props;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
