package com.wontlost.aster.aiw.pro;

import com.wontlost.agui.ee.governance.Redactor;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本服务自有的存储脱敏，占位符与 Pro 默认集（邮箱、电话、卡号、IBAN、IPv4）加类 Key 模式一致。
 *
 * <p>Pro 的 PatternRedactor 构造时会走 License.require；未授权时改用本实现，
 * 使落库副本仍然脱敏，同时不触达 Pro 授权闸门。电话规则不用 Pro 自带的 {@code phone()}：
 * 它把任意 8–12 位数字串（金额 999999999、日期 2026-10-22）都当电话，这里改用要求电话结构的 {@link #PHONE}，
 * 已授权路径也以自定义模式接入同一条规则。
 */
public final class LocalRedactor implements Redactor {
    /** 类 API Key：sk-/ak-/gsk-/xai- 加 16 位以上标识符。 */
    static final String API_KEY = "\\b(?:sk|ak|gsk|xai)[-_][A-Za-z0-9_-]{16,}\\b";

    /**
     * 电话：必须带电话结构，裸数字串、金额（千分位 / 小数）与 ISO 日期都不算。命中任一形态即可：
     * <ul>
     * <li>{@code +} 开头的国际号码（+86 138 0013 8000、+1 (555) 123-4567），+ 后至少 7 位数字；</li>
     * <li>中国大陆手机号 {@code 1[3-9]} 开头共 11 位，可按 3-4-4 以空格或连字符分组；</li>
     * <li>带括号区号：(04) 555-0192、(555) 123-4567；</li>
     * <li>0 开头的长途区号加两组分隔数字：021 123 4567、010-6552-9988；</li>
     * <li>北美 3-3-4 分组：555-123-4567、555.123.4567。</li>
     * </ul>
     * 两侧不得紧挨字母数字、小数点 / 千分位后的数字或 + -，避免截取更长数字串的一段。
     * 规则可独立使用（不依赖命中后的二次校验），因此也能交给 Pro 的 {@code pattern(name, regex, replacement)}。
     */
    static final String PHONE = "(?<![\\w.,+-])(?:"
            + "\\+(?=(?:[ .()-]{0,2}\\d){7})\\d{1,3}(?:[ .-]?\\(\\d{1,4}\\))?(?:[ .-]?\\d{1,5}){1,5}"
            + "|1[3-9]\\d(?:\\d{8}|([ -])\\d{4}\\1\\d{4})"
            + "|\\(\\d{2,4}\\)[ .-]?\\d{3,4}[ .-]?\\d{4}"
            + "|0\\d{1,4}[ .-]\\d{3,4}[ .-]\\d{3,4}"
            + "|\\d{3}([ .-])\\d{3}\\2\\d{4}"
            + ")(?!\\w|[.,-]?\\d)";

    private record Rule(Pattern pattern, String replacement, Predicate<String> verify) {
        static Rule of(String regex, String replacement) {
            return new Rule(Pattern.compile(regex), replacement, hit -> true);
        }
    }

    /** 电话放在最后：卡号与 IBAN 的数字分组先各归各位，不会被拆成「电话」。顺序须与 GovernanceConfig 一致。 */
    private static final List<Rule> RULES = List.of(
            Rule.of("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[email]"),
            new Rule(Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"), "[card]", LocalRedactor::luhn),
            Rule.of("\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{4}){3,7}(?:[ ]?[A-Z0-9]{1,4})?\\b", "[iban]"),
            Rule.of("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b", "[ip]"),
            Rule.of(API_KEY, "[key]"),
            Rule.of(PHONE, "[phone]"));

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
