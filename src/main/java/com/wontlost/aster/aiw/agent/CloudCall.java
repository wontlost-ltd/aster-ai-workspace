package com.wontlost.aster.aiw.agent;

/**
 * 一次 cloud {@code /api/llm/*} 调用。
 *
 * @param path     请求路径（不含 query），同时参与签名
 * @param jsonBody 原样发送且参与签名的 JSON 文本
 */
public record CloudCall(String path, String jsonBody) {}
