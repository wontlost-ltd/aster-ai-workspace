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

    /** generate 模式的翻译器：源码以代码块呈现。 */
    private static AsterFrameTranslator generator(String... ids) {
        var queue = new ArrayDeque<>(List.of(ids));
        return new AsterFrameTranslator(List.of(Message.user("hi")), queue::pop, true);
    }

    /** 把一段事件里某条消息的全部文本增量拼起来。 */
    private static String streamed(List<AgUiEvent> events, String messageId) {
        StringBuilder text = new StringBuilder();
        for (AgUiEvent e : events) {
            if (e instanceof AgUiEvent.TextMessageContent c && c.messageId().equals(messageId)) {
                text.append(c.delta());
            }
        }
        return text.toString();
    }

    private static List<AgUiEvent> feed(AsterFrameTranslator t, String... frames) {
        var events = new ArrayList<AgUiEvent>();
        for (String frame : frames) {
            events.addAll(t.onFrame(json(frame)));
        }
        return events;
    }

    private static String snapshotText(List<AgUiEvent> events) {
        var snap = events.stream().filter(AgUiEvent.MessagesSnapshot.class::isInstance)
                .map(AgUiEvent.MessagesSnapshot.class::cast).findFirst().orElseThrow();
        return snap.messages().get(snap.messages().size() - 1).content();
    }

    @Test
    void metaBecomesCustomModelEventAndModelStateDelta() {
        var ev = translator().onFrame(json("{\"type\":\"meta\",\"data\":\"test-model\"}"));
        assertEquals(2, ev.size());
        var custom = assertInstanceOf(AgUiEvent.Custom.class, ev.get(0));
        assertEquals("aster.model", custom.name());
        assertEquals("test-model", custom.value().asString());
        var patch = assertInstanceOf(AgUiEvent.StateDelta.class, ev.get(1)).delta().get(0);
        assertEquals("add", patch.get("op").asString());
        assertEquals("/model", patch.get("path").asString());
        assertEquals("test-model", patch.get("value").asString());
    }

    @Test
    void generateStreamIsFencedAndFinalSnapshotIsFencedSource() {
        var t = generator("m1", "m2");
        var ev = feed(t,
                "{\"type\":\"delta\",\"data\":\"Rule a\"}",
                "{\"type\":\"delta\",\"data\":\".\\n  x.\"}",
                "{\"type\":\"final\",\"data\":\"Rule a.\\n  x.\",\"validated\":true}");
        assertEquals("```aster\nRule a.\n  x.\n```", streamed(ev, "m1"));
        assertEquals("```aster\nRule a.\n  x.\n```", snapshotText(ev));
    }

    @Test
    void generateFinalDropsTrailingNewlinesSoCodeIsExactlyTheSource() {
        var ev = generator("m1").onFrame(json("{\"type\":\"final\",\"data\":\"Rule a.\\n\\n\",\"validated\":true}"));
        assertEquals("```aster\nRule a.\n```", snapshotText(ev));
    }

    @Test
    void generateRepairAttemptsAreEachFenced() {
        var t = generator("m1", "m2", "m3");
        var ev = feed(t,
                "{\"type\":\"delta\",\"data\":\"Rule bad\"}",
                "{\"type\":\"repair_start\",\"data\":1}",
                "{\"type\":\"delta\",\"data\":\"Rule good.\"}",
                "{\"type\":\"final\",\"data\":\"Rule good.\",\"validated\":true}");
        assertEquals("```aster\nRule bad\n```", streamed(ev, "m1"));
        assertEquals("```aster\nRule good.\n```", streamed(ev, "m2"));
        assertEquals("```aster\nRule good.\n```", snapshotText(ev));
    }

    @Test
    void generateDoesNotDoubleFenceAlreadyFencedText() {
        var t = generator("m1", "m2");
        var ev = feed(t,
                "{\"type\":\"delta\",\"data\":\"```aster\\nRule a.\"}",
                "{\"type\":\"delta\",\"data\":\"\\n```\"}",
                "{\"type\":\"final\",\"data\":\"```aster\\nRule a.\\n```\",\"validated\":true}");
        assertEquals("```aster\nRule a.\n```", streamed(ev, "m1"));
        assertEquals("```aster\nRule a.\n```", snapshotText(ev));
    }

    @Test
    void generateRecognisesAFenceSplitAcrossDeltas() {
        var t = generator("m1");
        var ev = feed(t,
                "{\"type\":\"delta\",\"data\":\"``\"}",
                "{\"type\":\"delta\",\"data\":\"`aster\\nRule a.\\n```\"}");
        ev.addAll(t.finish());
        assertEquals("```aster\nRule a.\n```", streamed(ev, "m1"));
    }

    @Test
    void generateSkipsLeadingBlankDeltasBeforeDecidingOnTheFence() {
        var t = generator("m1", "m2");
        var ev = feed(t,
                "{\"type\":\"delta\",\"data\":\"\\n\"}",
                "{\"type\":\"delta\",\"data\":\"```aster\\nRule a.\\n```\"}");
        ev.addAll(t.finish());
        assertEquals("```aster\nRule a.\n```", streamed(ev, "m1"));
    }

    @Test
    void generateFenceOutgrowsBackticksInsideTheSource() {
        var ev = generator("m1").onFrame(json("{\"type\":\"final\",\"data\":\"Note ```x```.\",\"validated\":false}"));
        assertEquals("````aster\nNote ```x```.\n````", snapshotText(ev));
    }

    @Test
    void generateBlankFinalStaysBlank() {
        var ev = generator("m1").onFrame(json("{\"type\":\"final\",\"data\":\"\",\"validated\":false}"));
        assertEquals("", snapshotText(ev));
    }

    @Test
    void suggestTextIsNotFenced() {
        var t = translator("m1", "m2");
        var ev = feed(t,
                "{\"type\":\"delta\",\"data\":\"Use x.\"}",
                "{\"type\":\"final\",\"data\":\"Use x.\",\"validated\":false}");
        assertEquals("Use x.", streamed(ev, "m1"));
        assertEquals("Use x.", snapshotText(ev));
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
        assertEquals(3, ev.size());
        assertEquals("m1", assertInstanceOf(AgUiEvent.TextMessageEnd.class, ev.get(0)).messageId());
        assertEquals("repair 1", assertInstanceOf(AgUiEvent.StepStarted.class, ev.get(1)).stepName());
        var patch = assertInstanceOf(AgUiEvent.StateDelta.class, ev.get(2)).delta().get(0);
        assertEquals("/repair", patch.get("path").asString());
        assertEquals(1, patch.get("value").asInt());
        var next = t.onFrame(json("{\"type\":\"delta\",\"data\":\"b\"}"));
        assertEquals("m2", assertInstanceOf(AgUiEvent.TextMessageStart.class, next.get(0)).messageId());
    }

    @Test
    void repairStartWithoutOpenMessageOnlyEmitsStep() {
        var ev = translator().onFrame(json("{\"type\":\"repair_start\",\"data\":2}"));
        assertEquals(2, ev.size());
        assertEquals("repair 2", assertInstanceOf(AgUiEvent.StepStarted.class, ev.get(0)).stepName());
        var patch = assertInstanceOf(AgUiEvent.StateDelta.class, ev.get(1)).delta().get(0);
        assertEquals("/repair", patch.get("path").asString());
    }

    @Test
    void statusResetClearsValidationRepairAndTruncationState() {
        var patch = AsterFrameTranslator.statusReset().delta();
        assertEquals(4, patch.size());
        var paths = new ArrayList<String>();
        for (JsonNode op : patch) {
            assertEquals("add", op.get("op").asString());
            assertTrue(op.get("value").isNull());
            paths.add(op.get("path").asString());
        }
        assertEquals(List.of("/validated", "/validationError", "/repair", "/truncated"), paths);
    }

    private static List<JsonNode> patches(List<AgUiEvent> events) {
        var ops = new ArrayList<JsonNode>();
        for (AgUiEvent e : events) {
            if (e instanceof AgUiEvent.StateDelta d) {
                d.delta().forEach(ops::add);
            }
        }
        return ops;
    }

    @Test
    void notValidatedFinalUsesTheFirstDiagnosticAsTheError() {
        var ev = generator("m1").onFrame(json("{\"type\":\"final\",\"data\":\"Rule a.\",\"validated\":false,"
                + "\"diagnostics\":[{\"message\":\"line 1: expected produce\",\"line\":1},{\"message\":\"x\"}]}"));
        var ops = patches(ev);
        assertEquals(2, ops.size());
        assertEquals("/validated", ops.get(0).get("path").asString());
        assertEquals("/validationError", ops.get(1).get("path").asString());
        assertEquals("line 1: expected produce", ops.get(1).get("value").get("message").asString());
    }

    @Test
    void diagnosticsAreIgnoredWhenValidatedOrEmpty() {
        assertEquals(1, patches(generator("m1").onFrame(json(
                "{\"type\":\"final\",\"data\":\"R.\",\"validated\":true,\"diagnostics\":[\"warn\"]}"))).size());
        assertEquals(1, patches(generator("m1").onFrame(json(
                "{\"type\":\"final\",\"data\":\"R.\",\"validated\":false,\"diagnostics\":[]}"))).size());
    }

    @Test
    void truncatedFinalSetsTheTruncatedState() {
        var ops = patches(translator("m1").onFrame(json("{\"type\":\"final\",\"data\":\"Define AUTO_APPROVE_L\",\"truncated\":true}")));
        assertEquals(1, ops.size());
        assertEquals("/truncated", ops.get(0).get("path").asString());
        assertTrue(ops.get(0).get("value").asBoolean());
        assertEquals(0, patches(translator("m1").onFrame(json("{\"type\":\"final\",\"data\":\"x\",\"truncated\":false}"))).size());
    }

    @Test
    void finalWithoutValidatedFieldEmitsNoValidationState() {
        var ev = translator("m1").onFrame(json("{\"type\":\"final\",\"data\":\"text\"}"));
        assertEquals(1, ev.size());
        assertInstanceOf(AgUiEvent.MessagesSnapshot.class, ev.get(0));
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
