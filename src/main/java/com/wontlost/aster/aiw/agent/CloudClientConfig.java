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
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var signer = new CloudRequestSigner(props.hmacKey(), Clock.systemUTC(), CloudRequestSigner.randomNonce());
        return new CloudLlmClient(http, props.cloudUrl(), signer);
    }
}
