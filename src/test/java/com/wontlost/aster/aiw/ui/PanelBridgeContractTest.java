package com.wontlost.aster.aiw.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 以文本守住 iframe 桥的协议字面量与 origin 规则（浏览器侧无法在单测里运行）。 */
class PanelBridgeContractTest {
    private static final Path BRIDGE = Path.of("src/main/frontend/aster-bridge.ts");

    @Test
    void declaresAllFiveV1MessageTypes() throws Exception {
        String ts = Files.readString(BRIDGE);
        for (String type : new String[] {"aster.ai.ready", "aster.ai.context", "aster.ai.apply",
                "aster.ai.applied", "aster.ai.reauth"}) {
            assertTrue(ts.contains("'" + type + "'"), type);
        }
        assertTrue(ts.contains("v: 1"));
        assertTrue(ts.contains("'agui-code-action'") && ts.contains("'agui-run-error'"));
        assertTrue(ts.contains("'session_expired'"));
    }

    @Test
    void neverPostsToAnyOrigin() throws Exception {
        String ts = Files.readString(BRIDGE);
        assertFalse(Pattern.compile("postMessage\\([^)]*'\\*'").matcher(ts).find());
        assertFalse(ts.contains("\"*\""));
        assertTrue(ts.contains("postMessage(message, cloudOrigin)"), "只向配置的 cloud origin 发送");
        assertTrue(ts.contains("event.origin !== cloudOrigin"), "只接受 cloud origin 的消息");
    }

    @Test
    void sendsTheContextItemsTheAgentReads() throws Exception {
        String ts = Files.readString(BRIDGE);
        for (String name : new String[] {"policy.source", "policy.cnlLocale", "policy.id"}) {
            assertTrue(ts.contains("'" + name + "'"), name);
        }
    }
}
