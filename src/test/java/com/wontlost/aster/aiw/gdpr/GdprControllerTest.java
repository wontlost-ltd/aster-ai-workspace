package com.wontlost.aster.aiw.gdpr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.wontlost.agui.AgUiJson;
import com.wontlost.agui.ee.governance.JdbcLegalHoldStore;
import com.wontlost.agui.ee.governance.LegalHold;
import com.wontlost.agui.model.Message;
import com.wontlost.agui.pro.audit.AuditEvent;
import com.wontlost.agui.pro.audit.JdbcAuditTrail;
import com.wontlost.agui.pro.conversations.Conversation;
import com.wontlost.agui.pro.conversations.ConversationScope;
import com.wontlost.agui.pro.conversations.JdbcConversationStore;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.aster.aiw.agent.CloudRequestSigner;
import com.wontlost.aster.aiw.auth.UserSessions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.wontlost.aster.aiw.pro.ProFeatures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/** cloud → 本服务 GDPR 导出 / 清除（F23）：签名、时间窗、重放、用户一致性，以及导出内容与带冻结的清除。 */
class GdprControllerTest {
    static final String KEY = "test-gdpr-key-0123456789abcdef-32b";
    private static final long NOW = 1_800_000_000L;
    private static final String ANN = "user-ann";
    private static final String BOB = "user-bob";

    private final DriverManagerDataSource ds = new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);
    private final JdbcConversationStore conversations;
    private final JdbcAuditTrail audit;
    private final JdbcLegalHoldStore holds;
    private final ErasureLedger ledger;
    private final UserSessions sessions = new UserSessions();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private AuditTrail auditForService;
    private int nonceSeq;

    GdprControllerTest() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-aiw.sql")).execute(ds);
        conversations = new JdbcConversationStore(ds).initializeSchema();
        audit = new JdbcAuditTrail(ds).initializeSchema();
        holds = new JdbcLegalHoldStore(ds).initializeSchema();
        ledger = new ErasureLedger(jdbc, clock, Duration.ofDays(180));
        auditForService = audit;
    }

    private MockMvc mvc(String key, boolean licensed) {
        var service = new GdprService(conversations, auditForService, holds, new ProFeatures(f -> licensed), clock,
                new TransactionTemplate(new DataSourceTransactionManager(ds)), () -> {}, ledger, sessions);
        var verifier = new GdprRequestVerifier(GdprRequestVerifier.effectiveKey(key, "llm-key"), clock);
        var controller = new GdprController(verifier, new InternalNonceStore(jdbc, clock), service, meters);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private MockMvc mvc() {
        return mvc(KEY, true);
    }

    private String nonce() {
        return String.format("%032x", ++nonceSeq);
    }

    /** 按 cloud 的做法签名：canonical = POST\npath\nts\nnonce\nsha256(body)\nuserId。 */
    static MockHttpServletRequestBuilder signed(String path, String body, String headerUser, long ts, String nonce,
            String key) {
        String sig = CloudRequestSigner.hmacHex(key,
                CloudRequestSigner.canonical("POST", path, Long.toString(ts), nonce, body, headerUser));
        return post(path).contentType("application/json").content(body)
                .header("X-Internal-Caller", "aster-cloud")
                .header("X-Aster-Timestamp", Long.toString(ts))
                .header("X-Aster-Nonce", nonce)
                .header("X-Aster-User-Id", headerUser)
                .header("X-Internal-Signature", sig);
    }

    private static String body(String userId) {
        return "{\"userId\":\"" + userId + "\",\"requestId\":\"req-1\"}";
    }

    private MockHttpServletResponse call(MockMvc mvc, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn().getResponse();
    }

    private static JsonNode json(MockHttpServletResponse r) throws Exception {
        return AgUiJson.mapper().readTree(r.getContentAsString());
    }

    private void seed() {
        ConversationScope ann = new ConversationScope(ANN, ANN);
        conversations.save(Conversation.start("t1", ann, "aster", List.of(Message.user("贷款上限是多少？"))).withTitle("贷款"));
        conversations.save(Conversation.start("t2", ann, "aster", List.of(Message.user("held question"))).withTitle("冻结"));
        conversations.save(Conversation.start("b1", new ConversationScope(BOB, BOB), "aster", List.of(Message.user("bob"))));
        audit.record(AuditEvent.of(ANN, ANN, "t1", null, "aster", AuditEvent.Kind.MESSAGE_SENT, "{\"text\":\"贷款上限是多少？\"}"));
        audit.record(AuditEvent.of(ANN, ANN, "t2", null, "aster", AuditEvent.Kind.MESSAGE_SENT, "{\"text\":\"held\"}"));
        audit.record(AuditEvent.of(ANN, ANN, null, null, "aster", AuditEvent.Kind.FEEDBACK, "{}"));
        audit.record(AuditEvent.of(BOB, BOB, "b1", null, "aster", AuditEvent.Kind.MESSAGE_SENT, "{\"text\":\"bob\"}"));
    }

    @Test
    void validSignatureExportsOnlyThatUsersConversationsAndAudit() throws Exception {
        seed();
        var r = call(mvc(), signed(GdprController.EXPORT, body(ANN), ANN, NOW, nonce(), KEY));
        assertEquals(200, r.getStatus());
        JsonNode out = json(r);
        assertEquals(ANN, out.get("userId").asString());
        assertTrue(out.get("persisted").asBoolean());
        assertEquals(2, out.get("conversations").size());
        JsonNode t1 = null;
        for (JsonNode n : out.get("conversations")) {
            if (n.get("threadId").asString().equals("t1")) {
                t1 = n;
            }
        }
        assertEquals("贷款", t1.get("title").asString());
        assertEquals("aster", t1.get("agentId").asString());
        assertFalse(t1.get("createdAt").asString().isBlank());
        assertFalse(t1.get("updatedAt").asString().isBlank());
        assertEquals("贷款上限是多少？", t1.get("messages").get(0).get("content").asString());
        assertEquals(3, out.get("audit").size());
        assertFalse(r.getContentAsString().contains("bob"));
    }

    @Test
    void withoutProLicenceExportIsEmptyAndNotPersisted() throws Exception {
        var r = call(mvc(KEY, false), signed(GdprController.EXPORT, body(ANN), ANN, NOW, nonce(), KEY));
        assertEquals(200, r.getStatus());
        JsonNode out = json(r);
        assertFalse(out.get("persisted").asBoolean());
        assertEquals(0, out.get("conversations").size());
        assertEquals(0, out.get("audit").size());
        assertFalse(out.get("conversationsTruncated").asBoolean());
        assertFalse(out.get("auditTruncated").asBoolean());
    }

    /** 早先授权期间落库、现在已未授权：仍要导出并能清除（persisted=false 只表示当前不再持久化）。 */
    @Test
    void dataFromAnEarlierLicensedPeriodIsStillExportedAndErased() throws Exception {
        seed();
        JsonNode out = json(call(mvc(KEY, false), signed(GdprController.EXPORT, body(ANN), ANN, NOW, nonce(), KEY)));
        assertFalse(out.get("persisted").asBoolean());
        assertEquals(2, out.get("conversations").size());
        assertEquals(3, out.get("audit").size());
        JsonNode erased = json(call(mvc(KEY, false), signed(GdprController.ERASE, body(ANN), ANN, NOW, nonce(), KEY)));
        assertEquals(2, erased.get("deletedConversations").asInt());
        assertEquals(0, conversations.list(new ConversationScope(ANN, ANN), 10).size());
    }

    @Test
    void storageOutageIs503() throws Exception {
        jdbc.execute("drop table aiw_internal_nonce");
        var r = call(mvc(), signed(GdprController.EXPORT, body(ANN), ANN, NOW, nonce(), KEY));
        assertEquals(503, r.getStatus());
        assertEquals("storage_unavailable", json(r).get("error").asString());
    }

    @Test
    void concurrentClaimsOfTheSameNonceAdmitExactlyOne() throws Exception {
        var store = new InternalNonceStore(jdbc, clock);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return store.claim("c".repeat(32), Instant.ofEpochSecond(NOW + 360));
                }));
            }
            start.countDown();
            int admitted = 0;
            for (var f : futures) {
                admitted += f.get() ? 1 : 0;
            }
            assertEquals(1, admitted);
        }
    }

    @Test
    void declaredOversizeBodyIs413WithoutReadingIt() throws Exception {
        String big = "{\"userId\":\"" + ANN + "\",\"pad\":\"" + "x".repeat(GdprController.MAX_BODY) + "\"}";
        var r = call(mvc(), signed(GdprController.EXPORT, big, ANN, NOW, nonce(), KEY));
        assertEquals(413, r.getStatus());
        assertEquals("body_too_large", json(r).get("error").asString());
    }

    @Test
    void headersAreCheckedBeforeTheBodySize() throws Exception {
        String big = "x".repeat(GdprController.MAX_BODY * 10);
        var unsigned = post(GdprController.EXPORT).content(big).header("X-Internal-Caller", "aster-cloud");
        assertEquals(401, call(mvc(), unsigned).getStatus());
    }

    /** 签名覆盖原始字节：无 Content-Type、非 ASCII 用户 id 也不会因字符集解码而误判。 */
    @Test
    void signatureCoversRawUtf8BytesWithoutContentType() throws Exception {
        String user = "用户-ä";
        String body = body(user);
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String n = nonce();
        String sig = CloudRequestSigner.hmacHex(KEY,
                CloudRequestSigner.canonical("POST", GdprController.EXPORT, Long.toString(NOW), n, bytes, user));
        var req = post(GdprController.EXPORT).content(bytes)
                .header("X-Internal-Caller", "aster-cloud").header("X-Aster-Timestamp", Long.toString(NOW))
                .header("X-Aster-Nonce", n).header("X-Aster-User-Id", user).header("X-Internal-Signature", sig);
        var r = call(mvc(), req);
        assertEquals(200, r.getStatus());
        assertEquals(user, AgUiJson.mapper().readTree(r.getContentAsByteArray()).get("userId").asString());
    }

    @Test
    void mostRelevantReasonWinsAndRejectionsAreCounted() throws Exception {
        var wrongCallerAndStale = post(GdprController.EXPORT).contentType("application/json").content(body(ANN))
                .header("X-Internal-Caller", "someone")
                .header("X-Aster-Timestamp", Long.toString(NOW - 1000))
                .header("X-Aster-Nonce", nonce())
                .header("X-Aster-User-Id", ANN)
                .header("X-Internal-Signature", "0".repeat(64));
        assertEquals("bad_signature", json(call(mvc(), wrongCallerAndStale)).get("error").asString(),
                "调用方不对时报 bad_signature，而不是 stale");
        assertEquals(1.0, meters.find("aiw.gdpr.rejected").tag("reason", "bad_signature").counter().count());
    }

    @Test
    void keyHygiene() {
        String good = "k".repeat(GdprRequestVerifier.MIN_KEY_BYTES);
        assertEquals(good, GdprRequestVerifier.effectiveKey("  " + good + "\n", "other"));
        assertEquals(null, GdprRequestVerifier.effectiveKey("short", "other"));
        assertEquals(null, GdprRequestVerifier.effectiveKey(good, good + "\n"), "不得与 AIW_HMAC_KEY 相同");
        assertEquals(null, GdprRequestVerifier.effectiveKey(" \n", "other"));
        assertEquals(null, GdprRequestVerifier.effectiveKey(null, "other"));
    }

    /** I3：清除后打开着的标签页回写整段历史，已清除的消息被剔除，新消息照常保存。 */
    @Test
    void eraseCannotBeUndoneByAnOpenTab() throws Exception {
        ConversationScope ann = new ConversationScope(ANN, ANN);
        Message old = Message.user("贷款上限是多少？");
        conversations.save(Conversation.start("t1", ann, "aster", List.of(old)));
        call(mvc(), signed(GdprController.ERASE, body(ANN), ANN, NOW, nonce(), KEY));

        var guarded = new ErasureAwareConversationStore(conversations, ledger);
        guarded.save(Conversation.start("t1", ann, "aster", List.of(old)));
        assertTrue(conversations.find(ann, "t1").isEmpty(), "只含已清除消息的回写被丢弃");

        Message fresh = Message.user("新问题");
        guarded.save(Conversation.start("t1", ann, "aster", List.of(old, Message.assistant("a1", "旧回答"), fresh)));
        Conversation saved = conversations.find(ann, "t1").orElseThrow();
        assertEquals(List.of("旧回答", "新问题"), saved.messages().stream().map(Message::content).toList(),
                "清除时不存在的消息照常保存");
        assertFalse(saved.title().contains("贷款"), "标题按剩余消息重算");

        guarded.save(Conversation.start("b1", new ConversationScope(BOB, BOB), "aster", List.of(old)));
        assertTrue(conversations.find(new ConversationScope(BOB, BOB), "b1").isPresent(), "墓碑按用户隔离");
    }

    @Test
    void eraseInvalidatesTheUsersSessionsOnThisInstance() throws Exception {
        var annSession = new org.springframework.mock.web.MockHttpSession();
        var bobSession = new org.springframework.mock.web.MockHttpSession();
        bind(annSession, ANN);
        bind(bobSession, BOB);
        JsonNode out = json(call(mvc(), signed(GdprController.ERASE, body(ANN), ANN, NOW, nonce(), KEY)));
        assertEquals(1, out.get("invalidatedSessions").asInt());
        assertTrue(annSession.isInvalid());
        assertFalse(bobSession.isInvalid());
    }

    private void bind(jakarta.servlet.http.HttpSession session, String userId) {
        var user = new com.wontlost.aster.aiw.auth.AiwUser(userId, "n", null, "zh", "member");
        session.setAttribute(com.wontlost.aster.aiw.auth.SessionUser.ATTRIBUTE, user);
        sessions.attributeAdded(new jakarta.servlet.http.HttpSessionBindingEvent(session,
                com.wontlost.aster.aiw.auth.SessionUser.ATTRIBUTE, user));
    }

    /** M1：清除在一个事务里——审计删除失败时对话也不删、墓碑也不写。 */
    @Test
    void eraseIsAtomic() throws Exception {
        seed();
        auditForService = new AuditTrail() {
            @Override
            public void record(AuditEvent e) {
                audit.record(e);
            }

            @Override
            public List<AuditEvent> query(Query q) {
                return audit.query(q);
            }

            @Override
            public int purge(Purge p) {
                audit.purge(p);
                throw new org.springframework.dao.DataAccessResourceFailureException("down");
            }
        };
        var r = call(mvc(), signed(GdprController.ERASE, body(ANN), ANN, NOW, nonce(), KEY));
        assertEquals(503, r.getStatus());
        assertEquals(2, conversations.list(new ConversationScope(ANN, ANN), 10).size());
        assertEquals(3, audit.query(AuditTrail.Query.tenant(ANN, 10)).size());
        assertTrue(ledger.erasedMessageIds(ANN).isEmpty());
    }

    @Test
    void eraseSkipsHeldThreadsAndIsIdempotent() throws Exception {
        seed();
        holds.place(LegalHold.thread(ANN, "t2", "litigation", "ops"));
        var r = call(mvc(), signed(GdprController.ERASE, body(ANN), ANN, NOW, nonce(), KEY));
        assertEquals(200, r.getStatus());
        JsonNode out = json(r);
        assertEquals(1, out.get("deletedConversations").asInt());
        assertEquals(2, out.get("deletedAudit").asInt(), "t1 的消息与无线程的反馈");
        assertEquals("t2", out.get("held").get(0).asString());
        assertEquals(1, out.get("held").size());
        assertFalse(out.get("tenantHold").asBoolean());
        assertEquals(List.of("t2"), conversations.list(new ConversationScope(ANN, ANN), 10).stream()
                .map(Conversation::threadId).toList());
        assertEquals(1, conversations.list(new ConversationScope(BOB, BOB), 10).size(), "其他用户不受影响");

        JsonNode again = json(call(mvc(), signed(GdprController.ERASE, body(ANN), ANN, NOW, nonce(), KEY)));
        assertEquals(0, again.get("deletedConversations").asInt());
        assertEquals(0, again.get("deletedAudit").asInt());
        assertEquals("t2", again.get("held").get(0).asString());
    }

    @Test
    void tenantWideHoldErasesNothingAndListsEveryThread() throws Exception {
        seed();
        holds.place(LegalHold.tenant(ANN, "audit", "ops"));
        JsonNode out = json(call(mvc(), signed(GdprController.ERASE, body(ANN), ANN, NOW, nonce(), KEY)));
        assertTrue(out.get("tenantHold").asBoolean());
        assertEquals(0, out.get("deletedConversations").asInt());
        assertEquals(2, out.get("held").size());
        assertEquals(2, conversations.list(new ConversationScope(ANN, ANN), 10).size());
    }

    /** f23-contract.md 中的示例向量：cloud 侧实现可用同一组值自测。 */
    @Test
    void contractExampleVector() {
        String body = "{\"userId\":\"clx0user123\",\"requestId\":\"gdpr-7f3a\"}";
        String canonical = CloudRequestSigner.canonical("POST", "/internal/gdpr/export", "1792670000",
                "0123456789abcdef0123456789abcdef", body, "clx0user123");
        assertEquals("POST\n/internal/gdpr/export\n1792670000\n0123456789abcdef0123456789abcdef\n"
                + "08355f4628d41ab94fd56a21be83849805253a2a05f5a52f8c1f59b419260751\nclx0user123", canonical);
        assertEquals("b4554bc2da70c9ad39ab93d9eb4167e729b808822f97ffc56f01b930946b0f5f",
                CloudRequestSigner.hmacHex("example-key-do-not-use-0123456789abcdef", canonical));
    }

    @Test
    void badSignatureIsRejected() throws Exception {
        var r = call(mvc(), signed(GdprController.EXPORT, body(ANN), ANN, NOW, nonce(), "wrong-key"));
        assertEquals(401, r.getStatus());
        assertEquals("bad_signature", json(r).get("error").asString());
    }

    /** 手工组装请求头，便于逐项篡改。 */
    private static MockHttpServletRequestBuilder raw(String path, String body, String caller, String nonce, String sig) {
        return post(path).contentType("application/json").content(body)
                .header("X-Internal-Caller", caller)
                .header("X-Aster-Timestamp", Long.toString(NOW))
                .header("X-Aster-Nonce", nonce)
                .header("X-Aster-User-Id", ANN)
                .header("X-Internal-Signature", sig);
    }

    @Test
    void tamperedBodyOrWrongPathOrCallerIsRejected() throws Exception {
        String n = nonce();
        String sig = CloudRequestSigner.hmacHex(KEY,
                CloudRequestSigner.canonical("POST", GdprController.EXPORT, Long.toString(NOW), n, body(ANN), ANN));
        assertEquals(401, call(mvc(), raw(GdprController.EXPORT, body(ANN) + " ", "aster-cloud", n, sig)).getStatus(),
                "请求体被改动");
        assertEquals(401, call(mvc(), raw(GdprController.ERASE, body(ANN), "aster-cloud", n, sig)).getStatus(),
                "签名绑定路径：导出签名不能用于清除");
        assertEquals(401, call(mvc(), raw(GdprController.EXPORT, body(ANN), "ai-workspace", n, sig)).getStatus(),
                "调用方必须是 aster-cloud");
        assertEquals(200, call(mvc(), raw(GdprController.EXPORT, body(ANN), "aster-cloud", n, sig.toUpperCase())).getStatus(),
                "签名 hex 大小写不敏感；以上拒绝均未消耗 nonce");
    }

    @Test
    void expiredTimestampIsRejectedAsStale() throws Exception {
        var past = call(mvc(), signed(GdprController.EXPORT, body(ANN), ANN, NOW - 301, nonce(), KEY));
        assertEquals(401, past.getStatus());
        assertEquals("stale_signature", json(past).get("error").asString());
        var future = call(mvc(), signed(GdprController.EXPORT, body(ANN), ANN, NOW + 301, nonce(), KEY));
        assertEquals("stale_signature", json(future).get("error").asString());
        assertEquals(200, call(mvc(), signed(GdprController.EXPORT, body(ANN), ANN, NOW - 300, nonce(), KEY)).getStatus());
    }

    @Test
    void replayedNonceIsRejectedAndRegisteredWithExpiry() throws Exception {
        String n = nonce();
        assertEquals(200, call(mvc(), signed(GdprController.ERASE, body(ANN), ANN, NOW, n, KEY)).getStatus());
        var replay = call(mvc(), signed(GdprController.ERASE, body(ANN), ANN, NOW, n, KEY));
        assertEquals(401, replay.getStatus());
        assertEquals("replayed_nonce", json(replay).get("error").asString());
        assertEquals(NOW + 360, jdbc.queryForObject("select expires_at from aiw_internal_nonce where nonce = ?",
                java.sql.Timestamp.class, n).toInstant().getEpochSecond());
    }

    @Test
    void expiredNoncesArePurged() {
        Clock later = Clock.fixed(Instant.ofEpochSecond(NOW + 1000), ZoneOffset.UTC);
        var store = new InternalNonceStore(jdbc, later);
        assertTrue(store.claim("a".repeat(32), Instant.ofEpochSecond(NOW + 360)));
        assertTrue(store.claim("b".repeat(32), Instant.ofEpochSecond(NOW + 2000)));
        store.purgeExpired();
        assertEquals(List.of("b".repeat(32)), jdbc.queryForList("select nonce from aiw_internal_nonce", String.class));
    }

    @Test
    void headerAndBodyUserMustMatch() throws Exception {
        var r = call(mvc(), signed(GdprController.EXPORT, body(BOB), ANN, NOW, nonce(), KEY));
        assertEquals(400, r.getStatus());
        assertEquals("user_mismatch", json(r).get("error").asString());
    }

    @Test
    void malformedOrMissingBodyIsBadRequest() throws Exception {
        for (String bad : List.of("", "[]", "{\"userId\":\"\"}", "{\"userId\":42}", "not json")) {
            var r = call(mvc(), signed(GdprController.EXPORT, bad, ANN, NOW, nonce(), KEY));
            assertEquals(400, r.getStatus(), bad);
            assertEquals("bad_request", json(r).get("error").asString());
        }
    }

    @Test
    void unsetOrUnusableKeyReturns503() throws Exception {
        for (String key : new String[] {"", null, "too-short", "llm-key" + " ".repeat(40)}) {
            var r = call(mvc(key, true), signed(GdprController.ERASE, body(ANN), ANN, NOW, nonce(), KEY));
            assertEquals(503, r.getStatus());
            assertEquals("gdpr_unconfigured", json(r).get("error").asString());
        }
    }
}
