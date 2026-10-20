package com.wontlost.aster.aiw.pro;

import com.wontlost.agui.pro.conversations.Conversation;
import com.wontlost.agui.pro.conversations.ConversationScope;
import com.wontlost.agui.pro.conversations.ConversationStore;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据库不可用时不阻断对话（ADR 0049 §2.7）：写失败记 WARN 并回调 {@code onSaveFailed}（界面据此提示「未能保存」），
 * 读失败按空结果返回。按视图创建，不注册为 bean，以免顶替 Pro 自动配置（及治理脱敏包装）的存储。
 */
public final class ResilientConversationStore implements ConversationStore {
    private static final Logger LOG = LoggerFactory.getLogger(ResilientConversationStore.class);

    private final ConversationStore delegate;
    private final Runnable onSaveFailed;

    public ResilientConversationStore(ConversationStore delegate, Runnable onSaveFailed) {
        this.delegate = delegate;
        this.onSaveFailed = onSaveFailed;
    }

    @Override
    public void save(Conversation conversation) {
        write("save", () -> {
            delegate.save(conversation);
            return null;
        }, null);
    }

    @Override
    public boolean delete(ConversationScope scope, String threadId) {
        return write("delete", () -> delegate.delete(scope, threadId), false);
    }

    @Override
    public int purge(Purge purge) {
        return write("purge", () -> delegate.purge(purge), 0);
    }

    @Override
    public Optional<Conversation> find(ConversationScope scope, String threadId) {
        return read("find", () -> delegate.find(scope, threadId), Optional.empty());
    }

    @Override
    public List<Conversation> list(ConversationScope scope, int limit) {
        return read("list", () -> delegate.list(scope, limit), List.of());
    }

    @Override
    public List<Conversation> search(ConversationScope scope, String query, int limit) {
        return read("search", () -> delegate.search(scope, query, limit), List.of());
    }

    private <T> T write(String op, Supplier<T> call, T fallback) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            LOG.warn("会话存储 {} 失败，本次对话未保存：{}", op, e.toString());
            onSaveFailed.run();
            return fallback;
        }
    }

    private static <T> T read(String op, Supplier<T> call, T fallback) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            LOG.warn("会话存储 {} 失败，按空结果处理：{}", op, e.toString());
            return fallback;
        }
    }
}
