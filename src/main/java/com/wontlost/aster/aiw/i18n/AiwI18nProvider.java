package com.wontlost.aster.aiw.i18n;

import com.vaadin.flow.i18n.I18NProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 界面文案（{@code i18n/ui_<en|zh|de|hi>.properties}，UTF-8）。Vaadin 通过该 bean 解析 {@code getTranslation}；
 * 视图与 {@code ChatFactory} 也直接按用户 locale 取文案。缺失的 locale 回落英文，缺失的键原样返回。
 */
@Component
public class AiwI18nProvider implements I18NProvider {
    private static final String DEFAULT = "en";
    private static final List<String> LANGUAGES = List.of("en", "zh", "de", "hi");
    private static final Map<String, Properties> BUNDLES = LANGUAGES.stream()
            .collect(Collectors.toUnmodifiableMap(Function.identity(), AiwI18nProvider::load));

    @Override
    public List<Locale> getProvidedLocales() {
        return LANGUAGES.stream().map(Locale::forLanguageTag).toList();
    }

    @Override
    public String getTranslation(String key, Locale locale, Object... params) {
        String text = text(locale == null ? null : locale.getLanguage(), key);
        return params.length == 0 ? text : java.text.MessageFormat.format(text, params);
    }

    /** 按界面语言短码取文案。 */
    public String text(String language, String key) {
        String own = bundle(language).getProperty(key);
        return own != null ? own : BUNDLES.get(DEFAULT).getProperty(key, key);
    }

    /** 某语言下的全部键（测试用来核对四语一致）。 */
    Set<String> keys(String language) {
        return bundle(language).stringPropertyNames();
    }

    private static Properties bundle(String language) {
        return BUNDLES.getOrDefault(language == null ? DEFAULT : language, BUNDLES.get(DEFAULT));
    }

    private static Properties load(String language) {
        String path = "/i18n/ui_" + language + ".properties";
        try (InputStream in = AiwI18nProvider.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺少界面文案资源 " + path);
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return props;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
