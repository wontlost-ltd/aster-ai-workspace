package com.wontlost.aster.aiw.pro;

import com.wontlost.agui.ee.governance.Redactor;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本服务自有的存储脱敏，规则与占位符与 Pro 默认集（邮箱、电话、卡号、IBAN、IPv4）加类 Key 模式一致。
 *
 * <p>Pro 的 PatternRedactor 构造时会走 License.require；未授权时改用本实现，
 * 使落库副本仍然脱敏，同时不触达 Pro 授权闸门。
 */
public final class LocalRedactor implements Redactor {
    /** 类 API Key：sk-/ak-/gsk-/xai- 加 16 位以上标识符。 */
    static final String API_KEY = "\\b(?:sk|ak|gsk|xai)[-_][A-Za-z0-9_-]{16,}\\b";

    private record Rule(Pattern pattern, String replacement, Predicate<String> verify) {
        static Rule of(String regex, String replacement) {
            return new Rule(Pattern.compile(regex), replacement, hit -> true);
        }
    }

    private static final List<Rule> RULES = List.of(
            Rule.of("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[email]"),
            new Rule(Pattern.compile(
                    "(?<!\\w|\\d\\.)(?:\\+?\\d{1,3}[ -]?)?(?:\\(\\d{1,4}\\)[ -]?)?\\d(?:[ -]?\\d){6,12}(?!\\w|\\.\\d)"),
                    "[phone]", hit -> between(digits(hit), 8, 12)),
            new Rule(Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"), "[card]", LocalRedactor::luhn),
            Rule.of("\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{4}){3,7}(?:[ ]?[A-Z0-9]{1,4})?\\b", "[iban]"),
            Rule.of("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b", "[ip]"),
            Rule.of(API_KEY, "[key]"));

    @Override
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Rule rule : RULES) {
            out = apply(rule, out);
        }
        return out;
    }

    private static String apply(Rule rule, String text) {
        Matcher m = rule.pattern().matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String hit = m.group();
            String replacement = rule.verify().test(hit) ? rule.replacement() : hit;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String digits(String hit) {
        return hit.replaceAll("[^0-9]", "");
    }

    private static boolean between(String digits, int min, int max) {
        return digits.length() >= min && digits.length() <= max;
    }

    /** Luhn 校验 13–19 位数字，避免把订单号当卡号。 */
    private static boolean luhn(String candidate) {
        String digits = digits(candidate);
        if (!between(digits, 13, 19)) {
            return false;
        }
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            sum += twice ? (d * 2 > 9 ? d * 2 - 9 : d * 2) : d;
            twice = !twice;
        }
        return sum % 10 == 0;
    }
}
