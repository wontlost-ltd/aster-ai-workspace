package com.wontlost.aster.aiw.agent;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.wontlost.aster.aiw.config.AiwProperties;

/** cloud 调用客户端的装配：JDK HttpClient + 服务签名，基础地址取 {@code aiw.cloud-url}。 */
@Configuration(proxyBeanMethods = false)
class CloudClientConfig {

    @Bean
    CloudLlmClient cloudLlmClient(AiwProperties props) {
        var signer = new CloudRequestSigner(props.hmacKey(), Clock.systemUTC(), CloudRequestSigner.randomNonce());
        return new CloudLlmClient(httpClient(), props.cloudUrl(), signer);
    }

    /**
     * 固定 HTTP/1.1（http 与 https 一致）：默认的 HTTP/2 在 {@code http://} 上会发 h2c 升级
     * （{@code Upgrade: h2c} + {@code HTTP2-Settings}），Next standalone 直接断开连接；
     * 每次 run 只有一条 SSE 长连接，HTTP/2 的多路复用没有收益，统一用 1.1 也避免两种协议路径。
     */
    static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
