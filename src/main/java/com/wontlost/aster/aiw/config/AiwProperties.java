package com.wontlost.aster.aiw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工作区自有配置（前缀 aiw）。
 *
 * @param cloudUrl         aster-cloud 的基础地址，服务端调用 /api/llm/* 使用
 * @param cloudOrigin      aster-cloud 的 origin，用于 frame-ancestors 与 postMessage 目标
 * @param hmacKey          服务到 cloud 的 HMAC-SHA256 签名密钥
 * @param handoffPublicKey 校验 handoff 令牌的 Ed25519 公钥（base64 X.509 DER）
 * @param ticketKey        短期票据密钥
 * @param retentionDays    会话与审计保留天数
 */
@ConfigurationProperties("aiw")
public record AiwProperties(
        String cloudUrl,
        String cloudOrigin,
        String hmacKey,
        String handoffPublicKey,
        String ticketKey,
        int retentionDays) {}
