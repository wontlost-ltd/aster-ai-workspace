package com.wontlost.aster.aiw.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.wontlost.agui.event.AgUiEvent;
import com.wontlost.agui.model.ContextItem;
import com.wontlost.agui.model.Message;
import com.wontlost.agui.model.RunAgentInput;
import com.wontlost.aster.aiw.auth.TicketUser;
import com.wontlost.aster.aiw.i18n.ErrorTexts;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RunRequestBuilderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TicketUser EN = new TicketUser("u1", "en");

    private static RunAgentInput input(String mode, List<ContextItem> ctx, Message... messages) {
        ObjectNode props = MAPPER.createObjectNode().put("ticket", "t");
        if (mode != null) {
            props.put("mode", mode);
        }
        return new RunAgentInput("th", "r1", null, List.of(messages), List.of(), ctx, props);
    }

    /** 期望构建成功，返回 cloud 调用。 */
    static CloudCall call(Either<CloudCall, AgUiEvent.RunError> e) {
        assertInstanceOf(Either.Left.class, e);
        return ((Either.Left<CloudCall, AgUiEvent.RunError>) e).value();
    }

    /** 期望构建被拒，返回错误事件。 */
    static AgUiEvent.RunError error(Either<CloudCall, AgUiEvent.RunError> e) {
        assertInstanceOf(Either.Right.class, e);
        return ((Either.Right<CloudCall, AgUiEvent.RunError>) e).value();
    }

    @Test
    void askIsDefaultModeAndUsesLastUserMessage() {
        var in = input(null, List.of(), Message.user("first"), Message.assistant("a1", "x"), Message.user("hello"));
        CloudCall c = call(RunRequestBuilder.build(in, EN));
        assertEquals("/api/llm/assistant", c.path());
        assertEquals("{\"query\":\"hello\",\"groundingHits\":[],\"locale\":\"en-US\"}", c.jsonBody());
        assertEquals(RunRequestBuilder.Mode.ASK, RunRequestBuilder.mode(in));
    }

    @Test
    void generateSendsGoalLocaleAndExistingSource() {
        var ctx = List.of(new ContextItem("policy.source", "Rule r"), new ContextItem("policy.cnlLocale", "de-DE"),
                new ContextItem("policy.id", "p1"));
        CloudCall c = call(RunRequestBuilder.build(input("generate", ctx, Message.user("make it")), EN));
        assertEquals("/api/llm/generate", c.path());
        assertEquals("{\"goal\":\"make it\",\"locale\":\"de-DE\",\"existingSource\":\"Rule r\"}", c.jsonBody());
    }

    @Test
    void generateWithoutEditorOmitsExistingSource() {
        CloudCall c = call(RunRequestBuilder.build(input("generate", List.of(), Message.user("g")),
                new TicketUser("u1", "zh")));
        assertEquals("{\"goal\":\"g\",\"locale\":\"zh-CN\"}", c.jsonBody());
    }

    @Test
    void suggestSendsSourceLocaleFocus() {
        var ctx = List.of(new ContextItem("policy.source", "Rule r"));
        CloudCall c = call(RunRequestBuilder.build(input("suggest", ctx), new TicketUser("u1", "de")));
        assertEquals("/api/llm/suggest", c.path());
        assertEquals("{\"source\":\"Rule r\",\"locale\":\"de-DE\",\"focus\":\"\"}", c.jsonBody());
    }

    /** 与 cloud normalizeLocale 一致：hi 没有 CNL 词法，回落 en-US。 */
    @Test
    void uiLocaleMapsToCloudCnlLocale() {
        assertEquals("en-US", RunRequestBuilder.cnlLocaleOf("en"));
        assertEquals("zh-CN", RunRequestBuilder.cnlLocaleOf("zh"));
        assertEquals("de-DE", RunRequestBuilder.cnlLocaleOf("de"));
        assertEquals("en-US", RunRequestBuilder.cnlLocaleOf("hi"));
        assertEquals("en-US", RunRequestBuilder.cnlLocaleOf(null));
    }

    @Test
    void blankCnlLocaleContextFallsBackToUiLocale() {
        var ctx = List.of(new ContextItem("policy.cnlLocale", " "));
        CloudCall c = call(RunRequestBuilder.build(input("ask", ctx, Message.user("q")), new TicketUser("u1", "zh")));
        assertEquals("{\"query\":\"q\",\"groundingHits\":[],\"locale\":\"zh-CN\"}", c.jsonBody());
    }

    @Test
    void oversizedSourceIsRejected() {
        var ctx = List.of(new ContextItem("policy.source", "x".repeat(65_537)));
        AgUiEvent.RunError e = error(RunRequestBuilder.build(input("suggest", ctx), new TicketUser("u1", "zh")));
        assertEquals("source_too_large", e.code());
        assertEquals(ErrorTexts.get("zh", "source_too_large"), e.message());
    }

    @Test
    void sourceAtLimitIsAccepted() {
        var ctx = List.of(new ContextItem("policy.source", "x".repeat(65_536)));
        call(RunRequestBuilder.build(input("suggest", ctx), EN));
    }

    @Test
    void oversizedGoalQueryFocusAreRejected() {
        var goal = input("generate", List.of(), Message.user("g".repeat(8_193)));
        var query = input("ask", List.of(), Message.user("q".repeat(2_049)));
        var focus = input("suggest", List.of(new ContextItem("policy.source", "s")), Message.user("f".repeat(513)));
        var existing = input("generate", List.of(new ContextItem("policy.source", "s".repeat(65_537))),
                Message.user("g"));
        for (var in : List.of(goal, query, focus, existing)) {
            assertEquals("source_too_large", error(RunRequestBuilder.build(in, EN)).code());
        }
    }

    @Test
    void unknownModeFallsBackToAsk() {
        assertEquals(RunRequestBuilder.Mode.ASK, RunRequestBuilder.mode(input("weird", List.of())));
        assertEquals(RunRequestBuilder.Mode.SUGGEST, RunRequestBuilder.mode(input("suggest", List.of())));
        assertEquals("generate", RunRequestBuilder.Mode.GENERATE.wire());
    }
}
