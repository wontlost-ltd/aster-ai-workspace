package com.wontlost.aster.aiw.ui;

import com.wontlost.agui.AgUiJson;
import com.wontlost.aster.aiw.agent.RunRequestBuilder.Mode;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/**
 * 一个聊天组件在整个视图生命周期内不变的部分：run 票据、策略 id、各模式的开场建议。
 * 切换模式时据此重建 forwardedProps，票据与策略 id 保持不变。
 */
record ChatProps(String ticket, String policyId, Map<Mode, List<String>> suggestions) implements Serializable {

    /** AsterAgent 读取的 forwardedProps：mode、ticket，以及（侧栏时）policyId。 */
    ObjectNode forwardedProps(Mode mode) {
        ObjectNode props = AgUiJson.mapper().createObjectNode().put("mode", mode.wire()).put("ticket", ticket);
        if (policyId != null) {
            props.put("policyId", policyId);
        }
        return props;
    }

    String[] suggestionsFor(Mode mode) {
        return suggestions.getOrDefault(mode, List.of()).toArray(String[]::new);
    }
}
