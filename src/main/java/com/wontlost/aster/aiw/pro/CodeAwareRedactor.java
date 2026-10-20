package com.wontlost.aster.aiw.pro;

import com.wontlost.agui.ee.governance.Redactor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码块感知的落库脱敏（ADR 0049 §2.7）：只对正文段落调用被包装的脱敏器，围栏代码块逐字节原样保留。
 *
 * <p>恢复的建议会被 Insert/Replace 写回编辑器，代码里的日期（2026-10-20）、金额（100000000）、
 * 邮箱、IP 若被替换成占位符就会损坏策略，因此代码完整性优先。
 * 代价：围栏代码块内的类 API Key 不会被脱敏——策略代码里不应出现密钥，这一取舍是有意的。
 *
 * <p>围栏规则（CommonMark 子集）：行首至多 3 个空格后接 ≥3 个反引号或波浪号开启，可带任意信息串
 * （反引号围栏的信息串不得含反引号）；只有同字符、长度不短于开启围栏、其后仅有空白的行才能关闭；
 * 未闭合的围栏视为代码一直延续到文末。
 */
public final class CodeAwareRedactor implements Redactor {
    private static final Pattern OPEN = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern LINE = Pattern.compile("[^\\n]*\\n|[^\\n]+$");

    private final Redactor prose;

    public CodeAwareRedactor(Redactor prose) {
        this.prose = prose;
    }

    /** 实际负责正文脱敏的实现（已授权为 Pro PatternRedactor，未授权为 LocalRedactor）。 */
    public Redactor delegate() {
        return prose;
    }

    @Override
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        StringBuilder pending = new StringBuilder();
        String fence = null;
        Matcher lines = LINE.matcher(text);
        while (lines.find()) {
            String line = lines.group();
            if (fence == null) {
                fence = opening(line);
                if (fence == null) {
                    pending.append(line);
                    continue;
                }
                flush(pending, out);
            } else if (closes(line, fence)) {
                fence = null;
            }
            out.append(line);
        }
        flush(pending, out);
        return out.toString();
    }

    private void flush(StringBuilder pending, StringBuilder out) {
        if (pending.isEmpty()) {
            return;
        }
        out.append(prose.redact(pending.toString()));
        pending.setLength(0);
    }

    /** 若该行开启围栏，返回围栏标记（如 "```"），否则返回 null。 */
    private static String opening(String line) {
        Matcher m = OPEN.matcher(stripEol(line));
        if (!m.matches()) {
            return null;
        }
        String marker = m.group(1);
        boolean backtickInfo = marker.charAt(0) == '`' && m.group(2).indexOf('`') >= 0;
        return backtickInfo ? null : marker;
    }

    private static boolean closes(String line, String fence) {
        String body = stripEol(line);
        int indent = 0;
        while (indent < body.length() && indent < 4 && body.charAt(indent) == ' ') {
            indent++;
        }
        if (indent > 3) {
            return false;
        }
        int run = indent;
        while (run < body.length() && body.charAt(run) == fence.charAt(0)) {
            run++;
        }
        return run - indent >= fence.length() && body.substring(run).isBlank();
    }

    private static String stripEol(String line) {
        return line.endsWith("\r\n") ? line.substring(0, line.length() - 2)
                : line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
    }
}
