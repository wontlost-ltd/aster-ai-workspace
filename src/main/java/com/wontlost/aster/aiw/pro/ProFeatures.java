package com.wontlost.aster.aiw.pro;

import com.wontlost.agui.pro.license.License;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pro 功能闸门（Review Focus #5）：仅当 license 有效且 key 覆盖该功能时启用。
 *
 * <p>Pro 绑定器内部会调用 {@code License.require}：开发模式试用期满即抛异常，生产模式挂横幅。
 * 本闸门在调用它们之前判定，无 key / key 无效时根本不触碰 Pro 绑定器，应用以基础聊天 + 会话内存历史运行。
 */
public class ProFeatures {
    private static final Logger LOG = LoggerFactory.getLogger(ProFeatures.class);

    private final Predicate<String> covers;
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    /** 测试与特殊部署可直接给出判定规则。 */
    public ProFeatures(Predicate<String> covers) {
        this.covers = covers;
    }

    /** 按 license 状态判定；状态由调用方提供，便于注入。 */
    public ProFeatures(Supplier<License.Status> status) {
        this(feature -> covers(status.get(), feature));
    }

    /** 生产装配：读取 agui-pro-license 的全局状态（agui.pro.license / AGUI_PRO_LICENSE 等）。 */
    public static ProFeatures fromLicense() {
        return new ProFeatures(License::status);
    }

    public boolean enabled(String feature) {
        boolean on = covers.test(feature);
        if (logged.add(feature)) {
            LOG.info("Pro 功能 {}：{}", feature, on ? "已启用" : "未授权，使用基础实现");
        }
        return on;
    }

    private static boolean covers(License.Status status, String feature) {
        return status.valid() && status.key().map(k -> k.covers(feature)).orElse(false);
    }
}
