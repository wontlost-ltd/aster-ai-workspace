package com.wontlost.aster.aiw.gdpr;

import com.wontlost.agui.AgUiJson;
import com.wontlost.agui.ee.governance.LegalHold;
import com.wontlost.agui.ee.governance.LegalHoldStore;
import com.wontlost.agui.pro.audit.AuditBinder;
import com.wontlost.agui.pro.audit.AuditEvent;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.agui.pro.conversations.Conversation;
import com.wontlost.agui.pro.conversations.ConversationBinder;
import com.wontlost.agui.pro.conversations.ConversationScope;
import com.wontlost.agui.pro.conversations.ConversationStore;
import com.wontlost.aster.aiw.pro.ProFeatures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 按用户导出 / 清除本服务保存的 AI 数据（F23）。用户键与 {@code HistoryBinding} 一致：租户 = 拥有者 = cloud 用户 id。
 *
 * <p>直接读写 Pro 的存储（不经 Resilient 包装），数据库故障要以 5xx 让 cloud 重试，而不是静默返回空。
 * 存储总是读：即使当前未授权，早先授权期间落库的数据也要能导出 / 清除；{@code persisted} 只表示
 * 当前是否在持久化（未授权时对话只在内存会话里，列表自然为空）。
 * 清除跳过法律冻结：线程冻结的线程保留，租户冻结时整个用户都不删；被保留的线程 id 列在 {@code held} 里。
 */
public class GdprService {
    /** 一次导出不截断：GDPR 导出须完整。 */
    static final int ALL = Integer.MAX_VALUE;

    private final ConversationStore conversations;
    private final AuditTrail audit;
    private final LegalHoldStore holds;
    private final ProFeatures pro;
    private final Clock clock;

    public GdprService(ConversationStore conversations, AuditTrail audit, LegalHoldStore holds, ProFeatures pro,
            Clock clock) {
        this.conversations = conversations;
        this.audit = audit;
        this.holds = holds;
        this.pro = pro;
        this.clock = clock;
    }

    ObjectNode export(String userId) {
        ObjectNode out = AgUiJson.mapper().createObjectNode();
        out.put("userId", userId);
        out.put("persisted", persisted());
        ArrayNode list = out.putArray("conversations");
        conversations.list(scope(userId), ALL).forEach(c -> list.add(conversation(c)));
        ArrayNode events = out.putArray("audit");
        audit.query(AuditTrail.Query.tenant(userId, ALL)).forEach(e -> events.add(event(e)));
        return out;
    }

    ObjectNode erase(String userId) {
        List<LegalHold> userHolds = holds.list(userId).stream().filter(h -> userId.equals(h.tenantId())).toList();
        boolean tenantHold = userHolds.stream().anyMatch(LegalHold::tenantWide);
        Set<String> held = new TreeSet<>(userHolds.stream().filter(h -> !h.tenantWide())
                .map(LegalHold::threadId).collect(Collectors.toSet()));
        int deletedConversations = 0;
        int deletedAudit = 0;
        if (tenantHold) {
            conversations.list(scope(userId), ALL).forEach(c -> held.add(c.threadId()));
        } else {
            // updated_at / at 早于「此刻 + 1 天」即该用户的全部记录；保留策略的 purge 已处理冻结与空 thread_id
            Instant cutoff = clock.instant().plus(Duration.ofDays(1));
            deletedConversations = conversations.purge(new ConversationStore.Purge(cutoff, userId, Set.of(), held));
            deletedAudit = audit.purge(new AuditTrail.Purge(cutoff, userId, Set.of(), held));
        }
        ObjectNode out = AgUiJson.mapper().createObjectNode();
        out.put("userId", userId);
        out.put("persisted", persisted());
        out.put("deletedConversations", deletedConversations);
        out.put("deletedAudit", deletedAudit);
        out.put("tenantHold", tenantHold);
        ArrayNode heldOut = out.putArray("held");
        held.forEach(heldOut::add);
        return out;
    }

    private boolean persisted() {
        return pro.enabled(ConversationBinder.FEATURE) || pro.enabled(AuditBinder.FEATURE);
    }

    private static ConversationScope scope(String userId) {
        return new ConversationScope(userId, userId);
    }

    private static ObjectNode conversation(Conversation c) {
        ObjectNode n = AgUiJson.mapper().createObjectNode();
        n.put("threadId", c.threadId());
        n.put("title", c.title());
        n.put("agentId", c.agentId());
        n.put("createdAt", String.valueOf(c.createdAt()));
        n.put("updatedAt", String.valueOf(c.updatedAt()));
        JsonNode messages = AgUiJson.mapper().valueToTree(c.messages() == null ? List.of() : c.messages());
        n.set("messages", messages);
        return n;
    }

    private static ObjectNode event(AuditEvent e) {
        ObjectNode n = AgUiJson.mapper().createObjectNode();
        n.put("id", e.id());
        n.put("at", String.valueOf(e.at()));
        n.put("kind", e.kind().name());
        n.put("threadId", e.threadId());
        n.put("runId", e.runId());
        n.put("agentId", e.agentId());
        n.put("actorId", e.actorId());
        n.put("payload", e.payload());
        return n;
    }
}
