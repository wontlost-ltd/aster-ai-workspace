package com.wontlost.aster.aiw.agent;

import java.util.List;
import java.util.Optional;

import com.wontlost.agui.event.AgUiEvent;
import com.wontlost.agui.model.ContextItem;
import com.wontlost.agui.model.Message;
import com.wontlost.agui.model.RunAgentInput;
import com.wontlost.aster.aiw.auth.TicketUser;
import com.wontlost.aster.aiw.i18n.ErrorTexts;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 把一次 AG-UI run 映射为一次 cloud 调用（ADR 0049 §2.2）。
 * <p>
 * 模式取自 {@code forwardedProps.mode}；编辑器上下文取自 context 条目
 * {@code policy.source} / {@code policy.cnlLocale}。长度上限与 aster-api DTO 一致，
 * 超限在本地拒绝，不浪费一次 cloud 调用（也不会产生计费记录）。
 */
public final class RunRequestBuilder {

    /** 运行模式：线上名、cloud 路径。 */
    public enum Mode {
        GENERATE("generate", "/api/llm/generate"),
        SUGGEST("suggest", "/api/llm/suggest"),
        ASK("ask", "/api/llm/assistant");

        private final String wire;
        private final String path;

        Mode(String wire, String path) {
            this.wire = wire;
            this.path = path;
        }

        public String wire() {
            return wire;
        }

        String path() {
            return path;
        }
    }

    static final String CTX_SOURCE = "policy.source";
    static final String CTX_CNL_LOCALE = "policy.cnlLocale";
    static final int MAX_SOURCE = 65_536;
    static final int MAX_GOAL = 8_192;
    static final int MAX_QUERY = 2_048;
    static final int MAX_FOCUS = 512;
    static final String TOO_LARGE = "source_too_large";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private RunRequestBuilder() {
    }

    /** 未知或缺省的模式按 ask 处理。 */
    public static Mode mode(RunAgentInput in) {
        String wire = in.forwardedProps() == null ? "" : in.forwardedProps().path("mode").asString("");
        for (Mode m : Mode.values()) {
            if (m.wire.equals(wire)) {
                return m;
            }
        }
        return Mode.ASK;
    }

    public static Either<CloudCall, AgUiEvent.RunError> build(RunAgentInput in, TicketUser user) {
        Mode mode = mode(in);
        String text = lastUserText(in.messages());
        String locale = context(in, CTX_CNL_LOCALE).orElseGet(() -> cnlLocaleOf(user.locale()));
        Optional<String> source = context(in, CTX_SOURCE);
        ObjectNode body = switch (mode) {
            case GENERATE -> generate(text, locale, source);
            case SUGGEST -> suggest(text, locale, source);
            case ASK -> ask(text, locale);
        };
        if (body == null) {
            return Either.right(new AgUiEvent.RunError(ErrorTexts.get(user.locale(), TOO_LARGE), TOO_LARGE, null));
        }
        return Either.left(new CloudCall(mode.path(), MAPPER.writeValueAsString(body)));
    }

    /**
     * 界面语言 → cloud 的 CNL locale，规则同 cloud {@code normalizeLocale}：
     * zh* → zh-CN，de* → de-DE，其余（含 hi，暂无印地语 CNL 词法）→ en-US。
     */
    static String cnlLocaleOf(String uiLocale) {
        String l = uiLocale == null ? "" : uiLocale;
        if (l.startsWith("zh")) {
            return "zh-CN";
        }
        return l.startsWith("de") ? "de-DE" : "en-US";
    }

    // ---- 各模式请求体；超限返回 null -----------------------------------

    private static ObjectNode generate(String goal, String locale, Optional<String> source) {
        if (goal.length() > MAX_GOAL || source.map(String::length).orElse(0) > MAX_SOURCE) {
            return null;
        }
        ObjectNode body = MAPPER.createObjectNode().put("goal", goal).put("locale", locale);
        source.ifPresent(s -> body.put("existingSource", s));
        return body;
    }

    private static ObjectNode suggest(String focus, String locale, Optional<String> source) {
        String src = source.orElse("");
        if (src.length() > MAX_SOURCE || focus.length() > MAX_FOCUS) {
            return null;
        }
        return MAPPER.createObjectNode().put("source", src).put("locale", locale).put("focus", focus);
    }

    private static ObjectNode ask(String query, String locale) {
        if (query.length() > MAX_QUERY) {
            return null;
        }
        ObjectNode body = MAPPER.createObjectNode().put("query", query);
        body.putArray("groundingHits");
        return body.put("locale", locale);
    }

    // ---- 输入提取 -------------------------------------------------------

    /** 最后一条 user 消息的正文；没有则为空串。 */
    private static String lastUserText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.role() == Message.Role.USER && m.content() != null) {
                return m.content();
            }
        }
        return "";
    }

    /** 按名称取 context 条目（description 即名称）；空白值视为缺失。 */
    private static Optional<String> context(RunAgentInput in, String name) {
        return in.context().stream()
                .filter(c -> name.equals(c.description()))
                .map(ContextItem::value)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }
}
