package com.wontlost.aster.aiw;

import com.wontlost.aster.aiw.config.AiwProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Aster AI 工作区入口：以 iframe 形式嵌入 aster-cloud 的 Vaadin + AG-UI 服务。 */
@SpringBootApplication
@EnableConfigurationProperties(AiwProperties.class)
@EnableScheduling
public class AiWorkspaceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiWorkspaceApplication.class, args);
    }
}
