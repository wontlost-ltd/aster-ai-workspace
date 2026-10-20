package com.wontlost.aster.aiw.pro;

import com.wontlost.agui.pro.audit.AuditEvent;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.agui.pro.conversations.Conversation;
import com.wontlost.agui.pro.conversations.ConversationScope;
import com.wontlost.agui.pro.conversations.ConversationStore;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessResourceFailureException;

/** 模拟数据库不可用：每个方法都抛 DataAccessException。 */
public final class DownStores {
    private DownStores() {}

    private static RuntimeException down() {
        return new DataAccessResourceFailureException("db down");
    }

    public static ConversationStore conversations() {
        return new ConversationStore() {
            public void save(Conversation c) { throw down(); }
            public Optional<Conversation> find(ConversationScope s, String t) { throw down(); }
            public List<Conversation> list(ConversationScope s, int l) { throw down(); }
            public List<Conversation> search(ConversationScope s, String q, int l) { throw down(); }
            public boolean delete(ConversationScope s, String t) { throw down(); }
            @Override public int purge(Purge p) { throw down(); }
        };
    }

    public static AuditTrail audit() {
        return new AuditTrail() {
            public void record(AuditEvent e) { throw down(); }
            public List<AuditEvent> query(Query q) { throw down(); }
        };
    }
}
