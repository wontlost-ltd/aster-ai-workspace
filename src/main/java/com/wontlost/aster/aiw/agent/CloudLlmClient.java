package com.wontlost.aster.aiw.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 以服务签名代表用户调用 cloud {@code /api/llm/*}，把 SSE 响应体按行交给调用方。
 * <p>
 * 返回的行流必须关闭；关闭会直接关掉底层响应输入流，因此可从另一个线程（取消回调）调用，
 * 正在阻塞读取的线程会立刻收到 IO 异常而退出，cloud 连接随之断开。
 */
public class CloudLlmClient {

    private static final Duration HEADERS_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_ERROR_BODY = 64 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final HttpClient http;
    private final String baseUrl;
    private final CloudRequestSigner signer;

    public CloudLlmClient(HttpClient http, String baseUrl, CloudRequestSigner signer) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.signer = signer;
    }

    /**
     * 发起调用并返回 SSE 行流；{@code cancelled} 为 true 后不再产出新行。
     *
     * @throws CloudDenied cloud 返回非 2xx
     * @throws IOException 连接失败、超时或被中断
     */
    public Stream<String> stream(CloudCall call, String userId, BooleanSupplier cancelled)
            throws CloudDenied, IOException {
        HttpResponse<InputStream> response = send(request(call, userId));
        InputStream body = response.body();
        if (response.statusCode() / 100 != 2) {
            throw denial(response.statusCode(), body);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        return reader.lines()
                .takeWhile(line -> !cancelled.getAsBoolean())
                .onClose(() -> closeQuietly(body));
    }

    private HttpRequest request(CloudCall call, String userId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + call.path()))
                .timeout(HEADERS_TIMEOUT)
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(call.jsonBody(), StandardCharsets.UTF_8));
        signer.headers("POST", call.path(), call.jsonBody(), userId).forEach(builder::header);
        return builder.build();
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("cloud 调用被中断", e);
        }
    }

    /** 读取有限长度的错误体并解析为错误码；读体失败时只保留状态码。 */
    private static CloudDenied denial(int status, InputStream body) {
        try (body) {
            String text = new String(body.readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
            return parseDenial(status, text);
        } catch (IOException e) {
            return new CloudDenied(status, "http_" + status, null);
        }
    }

    /**
     * 兼容两种 cloud 错误体：{@code {error:"code", message}} 与错误信封
     * {@code {error:{code, message}}}；非 JSON 时退化为 {@code http_<status>}。
     */
    static CloudDenied parseDenial(int status, String text) {
        JsonNode root = readJson(text);
        JsonNode error = root.path("error");
        JsonNode holder = error.isObject() ? error : root;
        String code = error.isObject() ? error.path("code").asString("") : error.asString("");
        String message = holder.path("message").isString() ? holder.path("message").asString() : null;
        return new CloudDenied(status, normaliseCode(code, status), message);
    }

    /** cloud 会话分支的 401 体是字面量 {@code Unauthorized}，与信封里的大写码一起统一成小写。 */
    private static String normaliseCode(String code, int status) {
        return code.isBlank() ? "http_" + status : code.toLowerCase(Locale.ROOT);
    }

    private static JsonNode readJson(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (RuntimeException e) {
            return MAPPER.missingNode();
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 关闭失败不影响取消语义：连接已不再被读取
        }
    }
}
