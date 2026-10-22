package com.wontlost.aster.aiw.gdpr;

import com.wontlost.agui.model.Message;
import com.wontlost.agui.pro.conversations.Conversation;
import com.wontlost.agui.pro.conversations.ConversationScope;
import com.wontlost.agui.pro.conversations.ConversationStore;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 让 GDPR 清除不被打开着的标签页撤销（F23 / I3）：Pro 的 ConversationBinder 每次运行结束都把客户端手里的
 * 整段对话 upsert 回存储；若用户在清除前打开了工作台，下一轮就会把已清除的消息写回去。
 *
 * <p>写入前剔除 {@link ErasureLedger} 里记为已清除的消息；剔除后为空则整次写入丢弃，
 * 有剔除时标题按剩余消息重算（原标题取自首条消息，可能正是被清除的内容）。其余操作原样委托。
 */
public final class ErasureAwareConversationStore implements ConversationStore {
    private static final Logger LOG = LoggerFactory.getLogger(ErasureAwareConversationStore.class);

    private final ConversationStore delegate;
    private final ErasureLedger ledger;

    public ErasureAwareConversationStore(ConversationStore delegate, ErasureLedger ledger) {
        this.delegate = delegate;
        this.ledger = ledger;
    }

    @Override
    public void save(Conversation c) {
        Set<String> erased = ledger.erasedMessageIds(c.scope().ownerId());
        if (erased.isEmpty()) {
            delegate.save(c);
            return;
        }
        List<Message> kept = c.messages().stream().filter(m -> m.id() == null || !erased.contains(m.id())).toList();
        if (kept.size() == c.messages().size()) {
            delegate.save(c);
            return;
        }
        if (kept.isEmpty()) {
            LOG.debug("丢弃只含已清除消息的会话写入");
            return;
        }
        delegate.save(new Conversation(c.threadId(), c.scope(), c.agentId(), Conversation.deriveTitle(kept), kept,
                c.state(), c.createdAt(), c.updatedAt()));
    }

    @Override
    public Optional<Conversation> find(ConversationScope scope, String threadId) {
        return delegate.find(scope, threadId);
    }

    @Override
    public List<Conversation> list(ConversationScope scope, int limit) {
        return delegate.list(scope, limit);
    }

    @Override
    public List<Conversation> search(ConversationScope scope, String query, int limit) {
        return delegate.search(scope, query, limit);
    }

    @Override
    public boolean delete(ConversationScope scope, String threadId) {
        return delegate.delete(scope, threadId);
    }

    @Override
    public int purge(Purge purge) {
        return delegate.purge(purge);
    }
}
