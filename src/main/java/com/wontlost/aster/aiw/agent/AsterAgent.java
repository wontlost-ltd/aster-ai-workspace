package com.wontlost.aster.aiw.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.wontlost.agui.agent.AgUiAgent;
import com.wontlost.agui.agent.AgUiEmitter;
import com.wontlost.agui.event.AgUiEvent;
import com.wontlost.agui.model.RunAgentInput;
import com.wontlost.aster.aiw.auth.TicketUser;
import com.wontlost.aster.aiw.auth.TicketVerifier;
import com.wontlost.aster.aiw.i18n.ErrorTexts;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

/**
 * AG-UI agent {@code aster}：代表票据中的用户调用 cloud {@code /api/llm/*}，把 SSE 帧翻译为 AG-UI 事件。
 * <p>
 * 每次 run 恰好一次 cloud 调用；首个错误后立即停止读流。错误一律以带 code 的 RUN_ERROR
 * 结束（文案按用户界面语言本地化），成功以 RUN_FINISHED 结束，二者之后都 {@code complete()}。
 * 票据校验器由后续任务提供：容器里没有该 bean 时，每次 run 都按会话过期处理。
 */
@Component("aster")
public class AsterAgent implements AgUiAgent {

    static final String SESSION_EXPIRED = "session_expired";
    static final String NETWORK = "network";
    static final String UNKNOWN = "unknown";
    static final String MISCONFIGURED = "cloud_misconfigured";
    private static final Logger LOG = LoggerFactory.getLogger(AsterAgent.class);

    private final ObjectProvider<TicketVerifier> tickets;
    private final CloudLlmClient cloud;
    private final MeterRegistry meters;

    public AsterAgent(ObjectProvider<TicketVerifier> tickets, CloudLlmClient cloud, MeterRegistry meters) {
        this.tickets = tickets;
        this.cloud = cloud;
        this.meters = meters;
    }

    /** run 的结局，用于收尾事件与指标标签。 */
    private record Outcome(String outcome, String code) {
        static final Outcome OK = new Outcome("ok", "none");
        static final Outcome CANCELLED = new Outcome("cancelled", "none");

        static Outcome error(String code) {
            return new Outcome("error", code);
        }
    }

    @Override
    public void run(RunAgentInput input, AgUiEmitter emitter) {
        emitter.emit(new AgUiEvent.RunStarted(input.threadId(), input.runId()));
        long started = System.nanoTime();
        Outcome outcome = execute(input, emitter);
        if (outcome == Outcome.OK) {
            emitter.emit(new AgUiEvent.RunFinished(input.threadId(), input.runId()));
        }
        emitter.complete();
        String mode = RunRequestBuilder.mode(input).wire();
        meters.counter("aiw.runs", "mode", mode, "outcome", outcome.outcome(), "code", outcome.code()).increment();
        // 耗时不带 code 标签，避免基数膨胀；无 Pro 授权时这是唯一的运行时长指标
        meters.timer("aiw.run.duration", "mode", mode, "outcome", outcome.outcome())
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    /**
     * run 的唯一兜底点：票据校验与 cloud 调用中的任何非受检异常（未配置 HMAC 密钥、非法 cloud 地址、
     * 校验器故障等）都属于服务端配置问题，记 ERROR 日志并以本地化的 cloud_misconfigured 结束，
     * 不把内部异常原文透给用户。
     */
    private Outcome execute(RunAgentInput input, AgUiEmitter emitter) {
        Optional<TicketUser> user;
        try {
            user = verify(input);
        } catch (RuntimeException e) {
            return misconfigured(emitter, null, input, e);
        }
        if (user.isEmpty()) {
            return fail(emitter, ErrorTexts.get(null, SESSION_EXPIRED), SESSION_EXPIRED);
        }
        try {
            return dispatch(input, user.get(), emitter);
        } catch (RuntimeException e) {
            return misconfigured(emitter, user.get().locale(), input, e);
        }
    }

    private Outcome dispatch(RunAgentInput input, TicketUser user, AgUiEmitter emitter) {
        return switch (RunRequestBuilder.build(input, user)) {
            case Either.Right<CloudCall, AgUiEvent.RunError> rejected -> fail(emitter, rejected.value());
            case Either.Left<CloudCall, AgUiEvent.RunError> call -> relay(call.value(), input, user, emitter);
        };
    }

    private static Outcome misconfigured(AgUiEmitter emitter, String locale, RunAgentInput input, RuntimeException e) {
        LOG.error("aster run 内部失败 (thread={}, run={})", input.threadId(), input.runId(), e);
        return fail(emitter, ErrorTexts.get(locale, MISCONFIGURED), MISCONFIGURED);
    }

    private Optional<TicketUser> verify(RunAgentInput input) {
        JsonNode props = input.forwardedProps();
        String ticket = props == null ? "" : props.path("ticket").asString("");
        TicketVerifier verifier = tickets.getIfAvailable();
        if (ticket.isBlank() || verifier == null) {
            return Optional.empty();
        }
        return verifier.verify(ticket);
    }

    /** 调用 cloud 并转发事件；取消时关闭响应流以断开 cloud 连接。 */
    private Outcome relay(CloudCall call, RunAgentInput input, TicketUser user, AgUiEmitter emitter) {
        boolean codeOnly = RunRequestBuilder.mode(input) == RunRequestBuilder.Mode.GENERATE;
        var translator = new AsterFrameTranslator(input.messages(), () -> UUID.randomUUID().toString(), codeOnly);
        try (Stream<String> lines = cloud.stream(call, user.userId(), emitter::isCancelled)) {
            emitter.onCancel(lines::close);
            return pump(lines.iterator(), translator, user.locale(), emitter);
        } catch (CloudDenied denied) {
            return fail(emitter, deniedText(user.locale(), denied), denied.code());
        } catch (IOException | UncheckedIOException e) {
            // 取消回调关闭流会让读取线程收到 IO 异常，这不是网络故障
            if (emitter.isCancelled()) {
                return Outcome.CANCELLED;
            }
            // 只记异常类与消息（不含请求体与密钥），便于排查 cloud 不可达；读流时的异常取其 IO 原因
            Throwable cause = e instanceof UncheckedIOException u ? u.getCause() : e;
            LOG.warn("cloud 调用网络失败 (path={}, run={}): {}: {}", call.path(), input.runId(),
                    cause.getClass().getName(), cause.getMessage());
            return fail(emitter, ErrorTexts.get(user.locale(), NETWORK), NETWORK);
        }
    }

    /** 逐行喂给帧解析器；遇到翻译出的 RUN_ERROR 即本地化后结束。 */
    private Outcome pump(Iterator<String> lines, AsterFrameTranslator translator, String locale, AgUiEmitter emitter) {
        StringBuilder carry = new StringBuilder();
        while (lines.hasNext()) {
            Optional<AgUiEvent.RunError> error = forward(lines.next() + "\n", carry, translator, emitter);
            if (error.isPresent()) {
                return fail(emitter, localize(locale, error.get()));
            }
        }
        if (emitter.isCancelled()) {
            return Outcome.CANCELLED;
        }
        // 末帧可能缺少结尾空行，补一个分隔符把它冲出来
        Optional<AgUiEvent.RunError> tail = forward("\n\n", carry, translator, emitter);
        if (tail.isPresent()) {
            return fail(emitter, localize(locale, tail.get()));
        }
        translator.finish().forEach(emitter::emit);
        return Outcome.OK;
    }

    /** 发出一段文本里补全的全部帧的事件；返回首个错误（不发出，由调用方本地化）。 */
    private static Optional<AgUiEvent.RunError> forward(String chunk, StringBuilder carry,
            AsterFrameTranslator translator, AgUiEmitter emitter) {
        for (JsonNode frame : SseFrameReader.parseFrames(chunk, carry)) {
            Optional<AgUiEvent.RunError> error = emitUntilError(translator.onFrame(frame), emitter);
            if (error.isPresent()) {
                return error;
            }
        }
        return Optional.empty();
    }

    private static Optional<AgUiEvent.RunError> emitUntilError(List<AgUiEvent> events, AgUiEmitter emitter) {
        for (AgUiEvent event : events) {
            if (event instanceof AgUiEvent.RunError error) {
                return Optional.of(error);
            }
            emitter.emit(event);
        }
        return Optional.empty();
    }

    // ---- 错误文案 -------------------------------------------------------

    /**
     * 流内错误帧：本地化文案 + 换行 + provider 原话（两者都有时）；
     * 未知码直接用帧里的原文（旧版 aster-api 只给一句中文说明而没有码）。
     */
    static AgUiEvent.RunError localize(String locale, AgUiEvent.RunError raw) {
        String text = ErrorTexts.get(locale, raw.code());
        String detail = raw.message() == null || raw.message().equals(raw.code()) ? null : raw.message();
        if (text == null) {
            return new AgUiEvent.RunError(raw.message(), raw.code(), null);
        }
        return new AgUiEvent.RunError(detail == null ? text : text + "\n" + detail, raw.code(), null);
    }

    /** HTTP 拒绝：cloud 的 message 是硬编码中文，仅在没有本地文案时兜底。 */
    private static String deniedText(String locale, CloudDenied denied) {
        String text = ErrorTexts.get(locale, denied.code());
        if (text != null) {
            return text;
        }
        return denied.serverMessage() != null ? denied.serverMessage() : ErrorTexts.get(locale, UNKNOWN);
    }

    private static Outcome fail(AgUiEmitter emitter, String message, String code) {
        return fail(emitter, new AgUiEvent.RunError(message, code, null));
    }

    private static Outcome fail(AgUiEmitter emitter, AgUiEvent.RunError error) {
        emitter.emit(error);
        return Outcome.error(error.code());
    }
}
