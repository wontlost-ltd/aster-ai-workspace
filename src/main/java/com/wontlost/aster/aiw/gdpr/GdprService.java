package com.wontlost.aster.aiw.gdpr;

import com.wontlost.agui.AgUiJson;
import com.wontlost.agui.ee.governance.LegalHold;
import com.wontlost.agui.ee.governance.LegalHoldStore;
import com.wontlost.agui.model.Message;
import com.wontlost.agui.pro.audit.AuditBinder;
import com.wontlost.agui.pro.audit.AuditEvent;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.agui.pro.conversations.Conversation;
import com.wontlost.agui.pro.conversations.ConversationBinder;
import com.wontlost.agui.pro.conversations.ConversationScope;
import com.wontlost.agui.pro.conversations.ConversationStore;
import com.wontlost.aster.aiw.auth.UserSessions;
import com.wontlost.aster.aiw.pro.ProFeatures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 按用户导出 / 清除本服务保存的 AI 数据（F23）。用户键与 {@code HistoryBinding} 一致：租户 = 拥有者 = cloud 用户 id。
 *
 * <p>直接读写 Pro 的存储（不经 Resilient 包装），数据库故障要以 5xx 让 cloud 重试，而不是静默返回空。
 * 存储总是读：即使当前未授权，早先授权期间落库的数据也要能导出 / 清除；{@code persisted} 只表示
 * 当前是否在持久化（未授权时对话只在内存会话里，列表自然为空）。
 *
 * <p>清除：
 * <ul>
 * <li>在一个事务里读法律冻结、记消息墓碑、删对话与审计（要么全删要么全不删）；PostgreSQL 上先以
 *     SHARE 锁住冻结表，事务提交前新增的冻结会等待，不会在「读冻结」与「删除」之间漏掉；</li>
 * <li>线程冻结的线程保留，租户冻结时整个用户都不删；被保留的线程 id 列在 {@code held} 里；</li>
 * <li>被删消息的 id 记入 {@link ErasureLedger}，打开着的标签页再回写时被剔除；提交后作废该用户在本实例的会话。</li>
 * </ul>
 *
 * <p>导出有上限（对话 {@value #MAX_CONVERSATIONS} 条、审计 {@value #MAX_AUDIT} 条），超出时对应的
 * {@code *Truncated} 为 true；保留期 180 天内正常用量远低于此。
 */
public class GdprService {
    static final int MAX_CONVERSATIONS = 2_000;
    static final int MAX_AUDIT = 20_000;
    /** 删除截止时间取「此刻 + 1 天」：覆盖该用户全部记录（含时钟略快的实例刚写入的）。 */
    private static final Duration ERASE_MARGIN = Duration.ofDays(1);

    private final ConversationStore conversations;
    private final AuditTrail audit;
    private final LegalHoldStore holds;
    private final ProFeatures pro;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Runnable lockHolds;
    private final ErasureLedger ledger;
    private final UserSessions sessions;

    public GdprService(ConversationStore conversations, AuditTrail audit, LegalHoldStore holds, ProFeatures pro,
            Clock clock, TransactionTemplate tx, Runnable lockHolds, ErasureLedger ledger, UserSessions sessions) {
        this.conversations = conversations;
        this.audit = audit;
        this.holds = holds;
        this.pro = pro;
        this.clock = clock;
        this.tx = tx;
        this.lockHolds = lockHolds;
        this.ledger = ledger;
        this.sessions = sessions;
    }

    ObjectNode export(String userId) {
        ObjectNode out = AgUiJson.mapper().createObjectNode();
        out.put("userId", userId);
        out.put("persisted", persisted());
        List<Conversation> convs = conversations.list(scope(userId), MAX_CONVERSATIONS + 1);
        ArrayNode list = out.putArray("conversations");
        convs.stream().limit(MAX_CONVERSATIONS).forEach(c -> list.add(conversation(c)));
        out.put("conversationsTruncated", convs.size() > MAX_CONVERSATIONS);
        List<AuditEvent> events = audit.query(AuditTrail.Query.tenant(userId, MAX_AUDIT + 1));
        ArrayNode auditOut = out.putArray("audit");
        events.stream().limit(MAX_AUDIT).forEach(e -> auditOut.add(event(e)));
        out.put("auditTruncated", events.size() > MAX_AUDIT);
        return out;
    }

    ObjectNode erase(String userId) {
        Result r = Objects.requireNonNull(tx.execute(status -> eraseInTransaction(userId)));
        int invalidated = sessions.invalidate(userId);
        ObjectNode out = AgUiJson.mapper().createObjectNode();
        out.put("userId", userId);
        out.put("persisted", persisted());
        out.put("deletedConversations", r.conversations());
        out.put("deletedAudit", r.audit());
        out.put("tenantHold", r.tenantHold());
        ArrayNode heldOut = out.putArray("held");
        r.held().forEach(heldOut::add);
        out.put("invalidatedSessions", invalidated);
        return out;
    }

    private record Result(int conversations, int audit, boolean tenantHold, Set<String> held) {}

    private Result eraseInTransaction(String userId) {
        lockHolds.run();
        List<LegalHold> userHolds = holds.list(userId).stream().filter(h -> userId.equals(h.tenantId())).toList();
        boolean tenantHold = userHolds.stream().anyMatch(LegalHold::tenantWide);
        Set<String> held = new TreeSet<>(userHolds.stream().filter(h -> !h.tenantWide())
                .map(LegalHold::threadId).collect(Collectors.toSet()));
        List<Conversation> all = conversations.list(scope(userId), Integer.MAX_VALUE);
        if (tenantHold) {
            all.forEach(c -> held.add(c.threadId()));
            return new Result(0, 0, true, held);
        }
        Instant now = clock.instant();
        Set<String> erasedIds = all.stream().filter(c -> !held.contains(c.threadId()))
                .flatMap(c -> c.messages().stream()).map(Message::id).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        ledger.record(userId, erasedIds, now);
        Instant cutoff = now.plus(ERASE_MARGIN);
        int deletedConversations = conversations.purge(new ConversationStore.Purge(cutoff, userId, Set.of(), held));
        int deletedAudit = audit.purge(new AuditTrail.Purge(cutoff, userId, Set.of(), held));
        return new Result(deletedConversations, deletedAudit, false, held);
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
        putInstant(n, "createdAt", c.createdAt());
        putInstant(n, "updatedAt", c.updatedAt());
        n.set("messages", AgUiJson.mapper().valueToTree(c.messages() == null ? List.of() : c.messages()));
        return n;
    }

    private static ObjectNode event(AuditEvent e) {
        ObjectNode n = AgUiJson.mapper().createObjectNode();
        n.put("id", e.id());
        putInstant(n, "at", e.at());
        n.put("kind", e.kind().name());
        n.put("threadId", e.threadId());
        n.put("runId", e.runId());
        n.put("agentId", e.agentId());
        n.put("actorId", e.actorId());
        n.put("payload", e.payload());
        return n;
    }

    /** ISO-8601 UTC 字符串；缺失时为 JSON null（而不是字符串 "null"）。 */
    private static void putInstant(ObjectNode n, String field, Instant value) {
        if (value == null) {
            n.putNull(field);
        } else {
            n.put(field, value.toString());
        }
    }
}
