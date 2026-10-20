package com.wontlost.aster.aiw.agent;

/**
 * cloud 以非 2xx 拒绝了调用（未登录、配额、封禁、BYOK、上游错误等）。
 * <p>
 * {@code code} 取自响应 JSON 的 {@code error}（字符串，或错误信封的 {@code error.code}），
 * 取不到时为 {@code http_<status>}；{@code serverMessage} 为 cloud 原文，可能为 null。
 */
public final class CloudDenied extends Exception {

    private final int status;
    private final String code;
    private final String serverMessage;

    public CloudDenied(int status, String code, String serverMessage) {
        super("cloud denied " + status + " " + code);
        this.status = status;
        this.code = code;
        this.serverMessage = serverMessage;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String serverMessage() {
        return serverMessage;
    }
}
