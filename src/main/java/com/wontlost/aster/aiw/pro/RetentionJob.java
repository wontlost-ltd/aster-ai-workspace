package com.wontlost.aster.aiw.pro;

import com.wontlost.agui.ee.governance.RetentionService;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 保留期清理的定时入口。Pro 的 RetentionService 构造即走 License.require，
 * 因此仅在 ee.governance 已授权时才向容器取它（它是懒加载 bean），未授权时整轮跳过。
 */
public class RetentionJob {
    private static final String FEATURE = "ee.governance";

    private final ProFeatures pro;
    private final ObjectProvider<RetentionService> service;
    private final AtomicReference<RetentionService.Result> last = new AtomicReference<>();

    public RetentionJob(ProFeatures pro, ObjectProvider<RetentionService> service) {
        this.pro = pro;
        this.service = service;
    }

    @Scheduled(cron = "${aiw.retention.cron:0 17 3 * * *}")
    public void run() {
        if (pro.enabled(FEATURE)) {
            last.set(service.getObject().run());
        }
    }

    /** 最近一次清理结果；从未执行（含未授权）时为空。 */
    public Optional<RetentionService.Result> lastRun() {
        return Optional.ofNullable(last.get());
    }
}
