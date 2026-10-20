package com.wontlost.aster.aiw.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把 cloud 的 SSE 字节流文本切成 JSON 帧。
 * <p>
 * 帧以空行分隔（兼容 LF 与 CRLF）；一帧内的多行 {@code data:} 以换行连接，
 * 其他字段行（{@code event:}、{@code id:}、注释）忽略。尾部不完整的帧留在 carry 中，
 * 等待下一个分片补全。纯函数，不做 I/O。
 */
final class SseFrameReader {

    private static final Pattern FRAME_SEPARATOR = Pattern.compile("\r?\n\r?\n");
    private static final Pattern LINE_BREAK = Pattern.compile("\r?\n");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private SseFrameReader() {
    }

    /**
     * 解析一个分片。
     *
     * @param chunk 新到达的文本
     * @param carry 上次遗留的不完整尾部；本方法会把它更新为新的尾部
     * @return 本次补全的全部帧（按到达顺序）
     */
    static List<JsonNode> parseFrames(String chunk, StringBuilder carry) {
        carry.append(chunk);
        String buffer = carry.toString();
        List<JsonNode> frames = new ArrayList<>();
        int start = 0;
        var matcher = FRAME_SEPARATOR.matcher(buffer);
        while (matcher.find()) {
            addFrame(frames, buffer.substring(start, matcher.start()));
            start = matcher.end();
        }
        carry.setLength(0);
        carry.append(buffer, start, buffer.length());
        return frames;
    }

    private static void addFrame(List<JsonNode> frames, String rawFrame) {
        String payload = extractData(rawFrame);
        if (payload == null || payload.isBlank() || "[DONE]".equals(payload.strip())) {
            return;
        }
        frames.add(toNode(payload));
    }

    /** 拼接帧内所有 data 行；没有 data 行返回 null。 */
    private static String extractData(String rawFrame) {
        List<String> parts = new ArrayList<>();
        for (String line : LINE_BREAK.split(rawFrame)) {
            if (line.startsWith("data:")) {
                String value = line.substring(5);
                parts.add(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    /** 能解析为 JSON 对象就直接用，否则视为纯文本增量。 */
    private static JsonNode toNode(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            if (node.isObject()) {
                return node;
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 负载走下面的文本增量分支
        }
        return MAPPER.createObjectNode().put("type", "delta").put("data", payload);
    }
}
