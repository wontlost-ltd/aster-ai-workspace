package com.wontlost.aster.aiw.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wontlost.agui.event.AgUiEvent;
import com.wontlost.agui.model.Message;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AsterFrameTranslatorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    private static AsterFrameTranslator translator(String... ids) {
        var queue = new ArrayDeque<>(List.of(ids));
        return new AsterFrameTranslator(List.of(Message.user("hi")), queue::pop);
    }

    @Test
    void metaBecomesCustomModelEvent() {
        var ev = translator().onFrame(json("{\"type\":\"meta\",\"data\":\"test-model\"}"));
        assertEquals(1, ev.size());
        var custom = assertInstanceOf(AgUiEvent.Custom.class, ev.get(0));
        assertEquals("aster.model", custom.name());
        assertEquals("test-model", custom.value().asString());
    }

    @Test
    void upstreamReachedAndEndEmitNothing() {
        var t = translator();
        assertTrue(t.onFrame(json("{\"type\":\"upstream_reached\"}")).isEmpty());
        assertTrue(t.onFrame(json("{\"type\":\"end\"}")).isEmpty());
        assertTrue(t.onFrame(json("{\"type\":\"unknown_future\"}")).isEmpty());
    }

    @Test
    void firstDeltaStartsMessageThenContent() {
        var ev = translator("m1").onFrame(json("{\"type\":\"delta\",\"data\":\"Rule \"}"));
        assertEquals(2, ev.size());
        var start = assertInstanceOf(AgUiEvent.TextMessageStart.class, ev.get(0));
        assertEquals("m1", start.messageId());
        assertEquals("assistant", start.role());
        var content = assertInstanceOf(AgUiEvent.TextMessageContent.class, ev.get(1));
        assertEquals("Rule ", content.delta());
    }

    @Test
    void secondDeltaOnlyEmitsContent() {
        var t = translator("m1");
        t.onFrame(json("{\"type\":\"delta\",\"data\":\"a\"}"));
        var ev = t.onFrame(json("{\"type\":\"delta\",\"data\":\"b\"}"));
        assertEquals(1, ev.size());
        assertEquals("m1", assertInstanceOf(AgUiEvent.TextMessageContent.class, ev.get(0)).messageId());
    }

    @Test
    void repairStartEndsMessageEmitsStepAndNextDeltaUsesNewId() {
        var t = translator("m1", "m2");
        t.onFrame(json("{\"type\":\"delta\",\"data\":\"a\"}"));
        var ev = t.onFrame(json("{\"type\":\"repair_start\",\"data\":1}"));
        assertEquals(2, ev.size());
        assertEquals("m1", assertInstanceOf(AgUiEvent.TextMessageEnd.class, ev.get(0)).messageId());
        assertEquals("repair 1", assertInstanceOf(AgUiEvent.StepStarted.class, ev.get(1)).stepName());
        var next = t.onFrame(json("{\"type\":\"delta\",\"data\":\"b\"}"));
        assertEquals("m2", assertInstanceOf(AgUiEvent.TextMessageStart.class, next.get(0)).messageId());
    }

    @Test
    void repairStartWithoutOpenMessageOnlyEmitsStep() {
        var ev = translator().onFrame(json("{\"type\":\"repair_start\",\"data\":2}"));
        assertEquals(1, ev.size());
        assertEquals("repair 2", assertInstanceOf(AgUiEvent.StepStarted.class, ev.get(0)).stepName());
    }

    @Test
    void validationErrorUsesErrorField() {
        var ev = translator().onFrame(json("{\"type\":\"validation_error\",\"error\":\"bad syntax\"}"));
        var patch = assertInstanceOf(AgUiEvent.StateDelta.class, ev.get(0)).delta().get(0);
        assertEquals("add", patch.get("op").asString());
        assertEquals("/validationError", patch.get("path").asString());
        assertEquals("bad syntax", patch.get("value").asString());
    }

    @Test
    void validationErrorFallsBackToDataField() {
        var ev = translator().onFrame(json("{\"type\":\"validation_error\",\"data\":\"oops\"}"));
        var patch = assertInstanceOf(AgUiEvent.StateDelta.class, ev.get(0)).delta().get(0);
        assertEquals("oops", patch.get("value").asString());
    }

    @Test
    void deltasShareOneMessageAndFinalSnapshots() {
        var t = translator("m1", "m2");
        var ev = new ArrayList<AgUiEvent>();
        ev.addAll(t.onFrame(json("{\"type\":\"delta\",\"data\":\"Rule \"}")));
        ev.addAll(t.onFrame(json("{\"type\":\"delta\",\"data\":\"a.\"}")));
        ev.addAll(t.onFrame(json("{\"type\":\"final\",\"data\":\"Rule a.\",\"validated\":true}")));
        assertInstanceOf(AgUiEvent.TextMessageStart.class, ev.get(0));
        assertEquals("m1", ((AgUiEvent.TextMessageContent) ev.get(2)).messageId());
        assertInstanceOf(AgUiEvent.TextMessageEnd.class, ev.get(3));
        var snap = (AgUiEvent.MessagesSnapshot) ev.get(4);
        assertEquals(2, snap.messages().size());
        assertEquals("hi", snap.messages().get(0).content());
        assertEquals("Rule a.", snap.messages().get(1).content());
        assertEquals(Message.Role.ASSISTANT, snap.messages().get(1).role());
        var delta = (AgUiEvent.StateDelta) ev.get(5);
        assertEquals("/validated", delta.delta().get(0).get("path").asString());
        assertTrue(delta.delta().get(0).get("value").asBoolean());
        assertTrue(t.finalized());
    }

    @Test
    void finalWithoutDeltasStillSnapshotsAndReportsNotValidated() {
        var t = translator("m1");
        var ev = t.onFrame(json("{\"type\":\"final\",\"data\":\"text\",\"validated\":false}"));
        assertEquals(2, ev.size());
        assertInstanceOf(AgUiEvent.MessagesSnapshot.class, ev.get(0));
        var delta = assertInstanceOf(AgUiEvent.StateDelta.class, ev.get(1));
        assertFalse(delta.delta().get(0).get("value").asBoolean());
    }

    @Test
    void errorWithMessageUsesBothFields() {
        var t = translator("m1");
        t.onFrame(json("{\"type\":\"delta\",\"data\":\"a\"}"));
        var ev = t.onFrame(json("{\"type\":\"error\",\"error\":\"provider_bad_request\","
                + "\"message\":\"bad\",\"providerErrorType\":\"invalid_request_error\"}"));
        assertInstanceOf(AgUiEvent.TextMessageEnd.class, ev.get(0));
        var err = assertInstanceOf(AgUiEvent.RunError.class, ev.get(1));
        assertEquals("provider_bad_request", err.code());
        assertEquals("bad", err.message());
    }

    @Test
    void errorWithOnlyErrorFieldUsesItAsMessage() {
        var ev = translator().onFrame(json("{\"type\":\"error\",\"error\":\"rate_limited\"}"));
        var err = assertInstanceOf(AgUiEvent.RunError.class, ev.get(0));
        assertEquals("rate_limited", err.code());
        assertEquals("rate_limited", err.message());
    }

    @Test
    void finishClosesOpenMessage() {
        var t = translator("m1");
        t.onFrame(json("{\"type\":\"delta\",\"data\":\"a\"}"));
        var ev = t.finish();
        assertEquals(1, ev.size());
        assertEquals("m1", assertInstanceOf(AgUiEvent.TextMessageEnd.class, ev.get(0)).messageId());
        assertTrue(t.finish().isEmpty());
    }

    @Test
    void finishAfterFinalEmitsNothing() {
        var t = translator("m1");
        t.onFrame(json("{\"type\":\"final\",\"data\":\"x\",\"validated\":true}"));
        assertTrue(t.finish().isEmpty());
    }
}
