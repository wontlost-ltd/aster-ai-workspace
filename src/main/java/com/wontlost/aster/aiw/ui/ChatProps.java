package com.wontlost.aster.aiw.ui;

import com.wontlost.agui.AgUiJson;
import com.wontlost.aster.aiw.agent.RunRequestBuilder.Mode;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/**
 * 一个聊天组件的 forwardedProps 来源：run 票据、策略 id、当前模式、各模式的开场建议。
 * 切换模式或续签票据时各自只换一项，其余保持不变。
 */
record ChatProps(String ticket, String policyId, Mode mode, Map<Mode, List<String>> suggestions)
        implements Serializable {

    ChatProps withMode(Mode next) {
        return new ChatProps(ticket, policyId, next, suggestions);
    }

    ChatProps withTicket(String next) {
        return new ChatProps(next, policyId, mode, suggestions);
    }

    /** AsterAgent 读取的 forwardedProps：mode、ticket，以及（侧栏时）policyId。 */
    ObjectNode forwardedProps() {
        ObjectNode props = AgUiJson.mapper().createObjectNode().put("mode", mode.wire()).put("ticket", ticket);
        if (policyId != null) {
            props.put("policyId", policyId);
        }
        return props;
    }

    String[] suggestionsForMode() {
        return suggestions.getOrDefault(mode, List.of()).toArray(String[]::new);
    }
}
