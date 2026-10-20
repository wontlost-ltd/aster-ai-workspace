package com.wontlost.aster.aiw.pro;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wontlost.agui.model.Message;
import com.wontlost.agui.pro.audit.AuditEvent;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.agui.pro.conversations.Conversation;
import com.wontlost.agui.pro.conversations.ConversationScope;
import com.wontlost.agui.pro.conversations.ConversationStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** ADR §2.7「数据库不可用」：写失败不外抛并回调一次，读失败返回空。 */
class ResilientStoresTest {
    private static final ConversationScope SCOPE = new ConversationScope("u1", "u1");
    private static final Conversation C = Conversation.start("t1", SCOPE, "aster", List.of(Message.user("hi")));

    @Test
    void failedSaveIsSwallowedAndReportedOnce() {
        AtomicInteger failures = new AtomicInteger();
        var store = new ResilientConversationStore(DownStores.conversations(), failures::incrementAndGet);
        assertDoesNotThrow(() -> store.save(C));
        assertEquals(1, failures.get());
    }

    @Test
    void failedReadsReturnEmpty() {
        var store = new ResilientConversationStore(DownStores.conversations(), () -> {});
        assertEquals(List.of(), store.list(SCOPE, 10));
        assertEquals(List.of(), store.search(SCOPE, "x", 10));
        assertEquals(Optional.empty(), store.find(SCOPE, "t1"));
    }

    @Test
    void failedDeleteAndPurgeAreWritesThatReport() {
        AtomicInteger failures = new AtomicInteger();
        var store = new ResilientConversationStore(DownStores.conversations(), failures::incrementAndGet);
        assertFalse(store.delete(SCOPE, "t1"));
        assertEquals(0, store.purge(ConversationStore.Purge.before(Instant.now())));
        assertEquals(2, failures.get());
    }

    @Test
    void healthyDelegateIsPassedThrough() {
        var store = new ResilientConversationStore(
                new com.wontlost.agui.pro.conversations.InMemoryConversationStore(), () -> {});
        store.save(C);
        assertEquals(Optional.of(C), store.find(SCOPE, "t1"));
        assertTrue(store.delete(SCOPE, "t1"));
    }

    @Test
    void failedAuditWritesAndQueriesNeverEscape() {
        AuditTrail down = DownStores.audit();
        var trail = new ResilientAuditTrail(down);
        assertDoesNotThrow(() -> trail.record(AuditEvent.of("u1", "u1", "t1", null, "aster",
                AuditEvent.Kind.RUN_STARTED, "{}")));
        assertEquals(List.of(), trail.query(AuditTrail.Query.tenant("u1", 10)));
    }
}
