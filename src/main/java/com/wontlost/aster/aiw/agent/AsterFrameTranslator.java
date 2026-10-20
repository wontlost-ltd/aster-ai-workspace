package com.wontlost.aster.aiw.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.wontlost.agui.event.AgUiEvent;
import com.wontlost.agui.model.Message;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * 把 cloud {@code /api/llm/*} 的 SSE 帧翻译为 AG-UI 事件（ADR 0049 §2.3）。
 * <p>
 * 每次 run 使用一个新实例，持有"当前打开的消息"状态；不做 I/O，也不依赖 Spring。
 * RUN_STARTED / RUN_FINISHED 由外层 agent 负责，这里只在 {@link #finish()} 收尾未关闭的消息。
 */
final class AsterFrameTranslator {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final List<Message> history;
    private final Supplier<String> idGen;
    private String current;
    private boolean finalized;

    AsterFrameTranslator(List<Message> history, Supplier<String> idGen) {
        this.history = List.copyOf(history);
        this.idGen = idGen;
    }

    /** 是否已收到 final 帧。 */
    boolean finalized() {
        return finalized;
    }

    List<AgUiEvent> onFrame(JsonNode frame) {
        String type = frame.path("type").asString("");
        return switch (type) {
            case "meta" -> onMeta(frame);
            case "delta" -> onDelta(frame);
            case "repair_start" -> onRepairStart(frame);
            case "validation_error" -> onValidationError(frame);
            case "final" -> onFinal(frame);
            case "error" -> onError(frame);
            default -> List.of(); // upstream_reached、end 及未知帧不外发
        };
    }

    /** 流结束时关闭仍打开的消息；RUN_FINISHED 由 agent 发出。 */
    List<AgUiEvent> finish() {
        return closeCurrent();
    }

    private List<AgUiEvent> onMeta(JsonNode frame) {
        return List.of(new AgUiEvent.Custom("aster.model", frame.path("data")));
    }

    private List<AgUiEvent> onDelta(JsonNode frame) {
        List<AgUiEvent> events = new ArrayList<>();
        if (current == null) {
            current = idGen.get();
            events.add(new AgUiEvent.TextMessageStart(current));
        }
        events.add(new AgUiEvent.TextMessageContent(current, frame.path("data").asString("")));
        return events;
    }

    private List<AgUiEvent> onRepairStart(JsonNode frame) {
        List<AgUiEvent> events = closeCurrent();
        events.add(new AgUiEvent.StepStarted("repair " + frame.path("data").asString("")));
        return events;
    }

    private List<AgUiEvent> onValidationError(JsonNode frame) {
        JsonNode detail = frame.has("error") ? frame.get("error") : frame.path("data");
        return List.of(new AgUiEvent.StateDelta(addOp("/validationError", detail)));
    }

    private List<AgUiEvent> onFinal(JsonNode frame) {
        List<AgUiEvent> events = closeCurrent();
        List<Message> messages = new ArrayList<>(history);
        messages.add(Message.assistant(idGen.get(), frame.path("data").asString("")));
        events.add(new AgUiEvent.MessagesSnapshot(messages));
        JsonNode validated = MAPPER.getNodeFactory().booleanNode(frame.path("validated").asBoolean(false));
        events.add(new AgUiEvent.StateDelta(addOp("/validated", validated)));
        finalized = true;
        return events;
    }

    private List<AgUiEvent> onError(JsonNode frame) {
        List<AgUiEvent> events = closeCurrent();
        String code = frame.path("error").asString("");
        String message = frame.hasNonNull("message") ? frame.get("message").asString() : code;
        events.add(new AgUiEvent.RunError(message, code, null));
        return events;
    }

    /** 若有打开的消息则发 TEXT_MESSAGE_END 并复位；返回可变列表供调用方续写。 */
    private List<AgUiEvent> closeCurrent() {
        List<AgUiEvent> events = new ArrayList<>();
        if (current != null) {
            events.add(new AgUiEvent.TextMessageEnd(current));
            current = null;
        }
        return events;
    }

    /** 构造单元素 JSON Patch：[{op:add,path,value}]。 */
    private static ArrayNode addOp(String path, JsonNode value) {
        ArrayNode patch = MAPPER.createArrayNode();
        patch.addObject().put("op", "add").put("path", path).set("value", value);
        return patch;
    }
}
