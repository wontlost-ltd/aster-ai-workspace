package com.wontlost.aster.aiw.web;

import com.vaadin.flow.component.page.ColorScheme;
import java.util.Locale;
import java.util.Optional;

/**
 * 亮 / 暗偏好（F29）：cloud 在 iframe 与直达链接上带 {@code ?theme=dark|light}；收到后记进会话，
 * 之后不带参数的整页加载（交接重定向、刷新）沿用。既无参数也无记录时跟随系统（prefers-color-scheme）。
 *
 * <p>会话里只存 {@code "dark"} / {@code "light"} 字符串，与 HttpSession / Vaadin WrappedSession 通用。
 */
public final class ColorSchemePreference {
    /** 查询参数名，与 cloud 约定。 */
    public static final String PARAM = "theme";
    /** 会话属性名。 */
    public static final String ATTRIBUTE = "aiw.colorScheme";

    private ColorSchemePreference() {}

    /** 只认 dark / light（大小写、首尾空白不敏感），其余值视为未指定。 */
    public static Optional<ColorScheme.Value> parse(Object raw) {
        if (!(raw instanceof String text)) {
            return Optional.empty();
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "dark" -> Optional.of(ColorScheme.Value.DARK);
            case "light" -> Optional.of(ColorScheme.Value.LIGHT);
            default -> Optional.empty();
        };
    }

    /** 参数优先，其次会话记录，都没有则跟随系统。 */
    public static ColorScheme.Value resolve(String param, Object remembered) {
        return parse(param).or(() -> parse(remembered)).orElse(ColorScheme.Value.SYSTEM);
    }

    /** 写进会话的值（{@code "dark"} / {@code "light"}）；参数无效时为空，不覆盖已有记录。 */
    public static Optional<String> remembered(String param) {
        return parse(param).map(ColorScheme.Value::getValue);
    }
}
