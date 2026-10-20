package com.wontlost.aster.aiw.ui;

import java.util.Optional;
import java.util.regex.Pattern;

/** 编辑器侧栏的查询参数：policyId 合法则沿用，否则为 {@value #NEW}；默认线程 {@code policy:{policyId}}。 */
final class PanelParams {
    static final String NEW = "new";
    private static final Pattern POLICY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private PanelParams() {}

    static String policyId(Optional<String> raw) {
        return raw.filter(id -> POLICY_ID.matcher(id).matches()).orElse(NEW);
    }

    static String threadId(String policyId) {
        return "policy:" + policyId;
    }
}
