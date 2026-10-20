package com.wontlost.aster.aiw.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.flow.component.ComponentUtil;
import com.wontlost.agui.AgUiChat;
import com.wontlost.agui.AgUiJson;
import com.wontlost.agui.model.Message;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.agui.pro.audit.InMemoryAuditTrail;
import com.wontlost.agui.pro.conversations.ConversationScope;
import com.wontlost.agui.pro.conversations.ConversationStore;
import com.wontlost.agui.pro.conversations.InMemoryConversationStore;
import com.wontlost.agui.pro.license.License;
import com.wontlost.aster.aiw.auth.AiwUser;
import com.wontlost.aster.aiw.pro.DownStores;
import com.wontlost.aster.aiw.pro.ProFeatures;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;

/**
 * 历史绑定（无 Spring、无浏览器）：直接向组件投递 RunFinishedEvent。
 * suggest/ask 不产生 MESSAGES_SNAPSHOT（裁定 R12），历史只来自客户端在运行结束时回传的消息列表。
 */
class HistoryBindingTest {
    private static final AiwUser ANN = new AiwUser("u1", "Ann", null, "en", "member");
    private static final List<Message> TURN = List.of(Message.user("why?"), Message.assistant("a1", "because"));

    @TempDir static Path proHome;

    /** Pro 绑定器内部会调用 License.require：开发模式试用文件落在临时目录，不碰开发机。 */
    @BeforeAll
    static void isolateLicenseHome() {
        System.setProperty(License.HOME_PROPERTY, proHome.toString());
        License.configure(null);
    }

    @AfterAll
    static void restoreLicenseHome() {
        System.clearProperty(License.HOME_PROPERTY);
        License.configure(null);
    }

    private static ProFeatures pro(boolean on) {
        return new ProFeatures(feature -> on);
    }

    private static AgUiChat.RunFinishedEvent finished(AgUiChat chat, String threadId, List<Message> messages) {
        ArrayNode node = (ArrayNode) AgUiJson.mapper().valueToTree(messages);
        return new AgUiChat.RunFinishedEvent(chat, true, threadId, "r1", false, node);
    }

    private static HistoryBinding bind(AgUiChat chat, ProFeatures pro, ConversationStore store, AuditTrail trail,
            SessionHistory history, Runnable onSaveFailed) {
        return HistoryBinding.bind(chat, ANN, "policy:p1", pro, store, trail, history, onSaveFailed);
    }

    @Test
    void withoutProTheSessionKeepsHistoryAcrossChats() {
        SessionHistory history = new SessionHistory();
        AgUiChat first = new AgUiChat("/agui/aster");
        bind(first, pro(false), new InMemoryConversationStore(), new InMemoryAuditTrail(), history, () -> {});
        assertEquals("policy:p1", first.getThreadId());

        ComponentUtil.fireEvent(first, finished(first, "policy:p1", TURN));

        AgUiChat second = new AgUiChat("/agui/aster");
        bind(second, pro(false), new InMemoryConversationStore(), new InMemoryAuditTrail(), history, () -> {});
        assertEquals(TURN, second.getMessages());
        assertEquals(List.of("policy:p1"), history.threadIds());
    }

    @Test
    void withoutProNothingReachesTheStoreOrTrail() {
        ConversationStore store = new InMemoryConversationStore();
        InMemoryAuditTrail trail = new InMemoryAuditTrail();
        AgUiChat chat = new AgUiChat("/agui/aster");
        bind(chat, pro(false), store, trail, new SessionHistory(), () -> {});
        ComponentUtil.fireEvent(chat, finished(chat, "policy:p1", TURN));

        assertTrue(store.list(new ConversationScope("u1", "u1"), 10).isEmpty());
        assertTrue(trail.query(AuditTrail.Query.tenant("u1", 10)).isEmpty());
    }

    @Test
    void withProTheConversationLandsInTheStoreAndRestores() {
        ConversationStore store = new InMemoryConversationStore();
        InMemoryAuditTrail trail = new InMemoryAuditTrail();
        SessionHistory history = new SessionHistory();
        AgUiChat chat = new AgUiChat("/agui/aster");
        bind(chat, pro(true), store, trail, history, () -> {});
        ComponentUtil.fireEvent(chat, finished(chat, "policy:p1", TURN));

        var scope = new ConversationScope("u1", "u1");
        assertEquals(TURN, store.find(scope, "policy:p1").orElseThrow().messages());
        assertEquals("aster", store.find(scope, "policy:p1").orElseThrow().agentId());
        assertTrue(history.threadIds().isEmpty(), "Pro 会话启用时不再写会话内存");
        assertTrue(!trail.query(AuditTrail.Query.tenant("u1", 10)).isEmpty(), "审计按用户租户记录");

        AgUiChat again = new AgUiChat("/agui/aster");
        bind(again, pro(true), store, trail, history, () -> {});
        assertEquals(TURN, again.getMessages());
    }

    @Test
    void databaseDownKeepsTheChatWorkingAndReportsOnce() {
        AtomicInteger failures = new AtomicInteger();
        ConversationStore down = DownStores.conversations();
        AuditTrail downTrail = DownStores.audit();
        AgUiChat chat = new AgUiChat("/agui/aster");
        bind(chat, pro(true), down, downTrail, new SessionHistory(), failures::incrementAndGet);

        ComponentUtil.fireEvent(chat, finished(chat, "policy:p1", TURN));

        assertEquals(1, failures.get());
        assertEquals(TURN, chat.getMessages(), "组件镜像不受存储失败影响");
    }

    @Test
    void sessionHistoryIsBoundedAndNewestFirst() {
        SessionHistory history = new SessionHistory();
        for (int i = 0; i < SessionHistory.MAX_THREADS + 5; i++) {
            history.save("t" + i, TURN);
        }
        assertEquals(SessionHistory.MAX_THREADS, history.threadIds().size());
        assertEquals("t" + (SessionHistory.MAX_THREADS + 4), history.threadIds().get(0));
        history.save("t10", TURN);
        assertEquals("t10", history.threadIds().get(0));
    }

    @Test
    void emptyRunsAreNotRemembered() {
        SessionHistory history = new SessionHistory();
        history.save("t1", List.of());
        assertTrue(history.threadIds().isEmpty());
    }
}
