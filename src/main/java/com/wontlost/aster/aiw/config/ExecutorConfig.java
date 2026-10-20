package com.wontlost.aster.aiw.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * 为 AG-UI 运行提供唯一的 AsyncTaskExecutor。
 *
 * <p>启用 @EnableScheduling 后容器里同时存在 applicationTaskExecutor 与 taskScheduler，
 * agui-vaadin 按类型取单个执行器会因歧义失败，所以显式声明一个 @Primary 的虚拟线程执行器；
 * AG-UI 单次运行是长时间阻塞的 SSE，虚拟线程最合适。
 */
@Configuration(proxyBeanMethods = false)
public class ExecutorConfig {
    @Bean
    @Primary
    AsyncTaskExecutor aguiRunExecutor() {
        var executor = new SimpleAsyncTaskExecutor("agui-run-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
