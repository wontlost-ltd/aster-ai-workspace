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
 * <p>
 * generate 模式下 cloud 流的是裸 CNL 源码；为让 {@code <agui-chat>} 渲染成带「插入 / 替换」按钮的代码块
 * （ADR 0049 §2.5），每条流式消息与最终快照都包成 {@code ```aster} 围栏；已自带围栏的文本原样保留。
 */
final class AsterFrameTranslator {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** 与 cloud 编辑器提取 aster 代码块时识别的语言标注一致。 */
    private static final String FENCE_LANG = "aster";
    private static final String FENCE = "```";

    private final List<Message> history;
    private final Supplier<String> idGen;
    private final boolean codeOnly;
    private String current;
    /** 当前消息的开围栏是否由本类补上（结束时需补闭围栏）。 */
    private boolean fenceAdded;
    private boolean finalized;

    AsterFrameTranslator(List<Message> history, Supplier<String> idGen) {
        this(history, idGen, false);
    }

    AsterFrameTranslator(List<Message> history, Supplier<String> idGen, boolean codeOnly) {
        this.history = List.copyOf(history);
        this.idGen = idGen;
        this.codeOnly = codeOnly;
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

    /** 保留 CUSTOM 事件，另以状态增量 /model 让界面经 state-changed 显示实际模型。 */
    private List<AgUiEvent> onMeta(JsonNode frame) {
        JsonNode model = frame.path("data");
        return List.of(new AgUiEvent.Custom("aster.model", model),
                new AgUiEvent.StateDelta(addOp("/model", model)));
    }

    private List<AgUiEvent> onDelta(JsonNode frame) {
        String text = frame.path("data").asString("");
        // generate 要看到首段非空内容才能判断是否已自带围栏；之前的空白分片丢弃无害
        if (current == null && codeOnly && text.isBlank()) {
            return List.of();
        }
        List<AgUiEvent> events = current == null ? startMessage(text) : new ArrayList<>();
        events.add(new AgUiEvent.TextMessageContent(current, text));
        return events;
    }

    /** 开始新消息；generate 且首段未自带围栏时先发开围栏。 */
    private List<AgUiEvent> startMessage(String firstText) {
        current = idGen.get();
        List<AgUiEvent> events = new ArrayList<>();
        events.add(new AgUiEvent.TextMessageStart(current));
        // 围栏可能被拆在多个分片里（如先到 "``"），CNL 源码不会以反引号开头，故首个反引号即视为自带围栏
        fenceAdded = codeOnly && !firstText.stripLeading().startsWith("`");
        if (fenceAdded) {
            events.add(new AgUiEvent.TextMessageContent(current, FENCE + FENCE_LANG + "\n"));
        }
        return events;
    }

    /**
     * 修复开始：除 STEP_STARTED 外再发状态增量 {@code /repair}，
     * 因为 Flow 端的 {@code AgUiChat} 不暴露 step 事件，界面只能经 state-changed 显示修复进度。
     */
    private List<AgUiEvent> onRepairStart(JsonNode frame) {
        List<AgUiEvent> events = closeCurrent();
        JsonNode attempt = frame.path("data");
        events.add(new AgUiEvent.StepStarted("repair " + attempt.asString("")));
        events.add(new AgUiEvent.StateDelta(addOp("/repair", attempt)));
        return events;
    }

    /** run 开始时清空上次的校验 / 修复状态（置 null），避免状态跨 run 残留在提示里。 */
    static AgUiEvent.StateDelta statusReset() {
        ArrayNode patch = MAPPER.createArrayNode();
        for (String path : List.of("/validated", "/validationError", "/repair")) {
            patch.add(addOp(path, MAPPER.getNodeFactory().nullNode()).get(0));
        }
        return new AgUiEvent.StateDelta(patch);
    }

    private List<AgUiEvent> onValidationError(JsonNode frame) {
        JsonNode detail = frame.has("error") ? frame.get("error") : frame.path("data");
        return List.of(new AgUiEvent.StateDelta(addOp("/validationError", detail)));
    }

    private List<AgUiEvent> onFinal(JsonNode frame) {
        List<AgUiEvent> events = closeCurrent();
        List<Message> messages = new ArrayList<>(history);
        String text = frame.path("data").asString("");
        messages.add(Message.assistant(idGen.get(), codeOnly ? fenced(text) : text));
        events.add(new AgUiEvent.MessagesSnapshot(messages));
        // 只有带编译结论的 final（generate）才发 /validated，问答类不显示「未通过校验」
        if (frame.has("validated")) {
            JsonNode validated = MAPPER.getNodeFactory().booleanNode(frame.path("validated").asBoolean(false));
            events.add(new AgUiEvent.StateDelta(addOp("/validated", validated)));
        }
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

    /** 若有打开的消息则（补闭围栏后）发 TEXT_MESSAGE_END 并复位；返回可变列表供调用方续写。 */
    private List<AgUiEvent> closeCurrent() {
        List<AgUiEvent> events = new ArrayList<>();
        if (current == null) {
            return events;
        }
        if (fenceAdded) {
            events.add(new AgUiEvent.TextMessageContent(current, "\n" + FENCE));
        }
        events.add(new AgUiEvent.TextMessageEnd(current));
        current = null;
        fenceAdded = false;
        return events;
    }

    private static boolean isFenced(String text) {
        return text.stripLeading().startsWith(FENCE);
    }

    /**
     * 把校验后的源码包成代码块：去掉末尾换行，使代码块文本（{@code <code>.textContent}）
     * 恰为源码加 marked 补的一个换行；围栏比源码里最长的反引号串多一个，避免被提前闭合。
     */
    static String fenced(String source) {
        if (source.isBlank() || isFenced(source)) {
            return source;
        }
        String body = source.replaceAll("[\r\n]+$", "");
        String fence = "`".repeat(Math.max(FENCE.length(), longestBacktickRun(body) + 1));
        return fence + FENCE_LANG + "\n" + body + "\n" + fence;
    }

    private static int longestBacktickRun(String text) {
        int longest = 0;
        int run = 0;
        for (char c : text.toCharArray()) {
            run = c == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    /** 构造单元素 JSON Patch：[{op:add,path,value}]。 */
    private static ArrayNode addOp(String path, JsonNode value) {
        ArrayNode patch = MAPPER.createArrayNode();
        patch.addObject().put("op", "add").put("path", path).set("value", value);
        return patch;
    }
}
