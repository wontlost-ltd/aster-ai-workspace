package com.wontlost.aster.aiw.db;

import java.sql.Connection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时核对 ai_workspace schema 的归属（ADR 0050 §2.1.4，见 aster-api 仓 docs/adr/0050）。
 *
 * <p>本服务以单一角色（aster_ai_user）同时建表与读写，因此约束很简单：schema 与其中全部表的 owner 必须是当前连接角色，
 * 或当前角色所属的组角色（用 pg_has_role 判定成员关系）。归属一旦漂移（例如由超级用户手工建表），sql-init 的
 * CREATE TABLE IF NOT EXISTS 看似成功，运行时却会在第一次写入时报权限错误。把检查放在启动期：strict 模式下 ApplicationRunner
 * 抛异常使 Spring Boot 启动失败、Pod 不就绪，而不是等用户触发。
 *
 * <p>模式由 aiw.db.owner-check 决定：off 不检查；lenient 只告警（本地/CI，非 PostgreSQL 自动跳过）；strict 不符即启动失败（生产）。
 */
@Component
public class SchemaOwnerCheck implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(SchemaOwnerCheck.class);
    static final String SCHEMA = "ai_workspace";

    /** 数据库探针：把 JDBC 查询与判定逻辑分开，便于用桩覆盖所有分支。 */
    interface Probe {
        String productName() throws Exception;
        String currentUser();
        /** schema 不存在时返回 null（首次启动、sql-init 之前属正常）。 */
        String schemaOwner();
        /** 当前角色既不是 owner 也不是 owner 组成员的表。 */
        List<String> foreignTables();
    }

    private final Probe probe;
    private final String mode;

    @Autowired
    public SchemaOwnerCheck(JdbcTemplate jdbc, @Value("${aiw.db.owner-check:lenient}") String mode) {
        this(new JdbcProbe(jdbc), mode);
    }

    SchemaOwnerCheck(Probe probe, String mode) {
        if (!List.of("off", "lenient", "strict").contains(mode.toLowerCase())) {
            throw new IllegalArgumentException("aiw.db.owner-check 只接受 off / lenient / strict，当前为: " + mode);
        }
        this.probe = probe;
        this.mode = mode.toLowerCase();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if ("off".equalsIgnoreCase(mode)) return;
        String product = probe.productName();
        if (!product.toLowerCase().contains("postgresql")) {
            LOG.info("owner-selfcheck: 非 PostgreSQL（{}），跳过", product);
            return;
        }
        Verdict verdict = evaluate(mode, probe.currentUser(), probe.schemaOwner(), probe.foreignTables());
        if (verdict.ok()) {
            LOG.info("owner-selfcheck: ok (schema={} owner={} foreignTables=0)", SCHEMA, probe.currentUser());
            return;
        }
        if (verdict.fatal()) {
            throw new IllegalStateException("owner-selfcheck: mismatch: " + verdict.detail());
        }
        LOG.warn("owner-selfcheck: mismatch (lenient): {}", verdict.detail());
    }

    /** 纯判定：schemaOwner 为 null 表示 schema 不存在；foreignTables 已由探针按组成员关系过滤。 */
    static Verdict evaluate(String mode, String currentUser, String schemaOwner, List<String> foreignTables) {
        StringBuilder detail = new StringBuilder();
        if (schemaOwner != null && !schemaOwner.equals(currentUser)) {
            detail.append("schema ").append(SCHEMA).append(" owner is ").append(schemaOwner)
                .append(", expected ").append(currentUser).append(';');
        }
        if (!foreignTables.isEmpty()) {
            detail.append(" tables not owned by ").append(currentUser).append(": ").append(foreignTables).append(';');
        }
        boolean ok = detail.isEmpty();
        boolean fatal = !ok && "strict".equalsIgnoreCase(mode);
        return new Verdict(ok, fatal, detail.toString().trim());
    }

    record Verdict(boolean ok, boolean fatal, String detail) {}

    /** 生产探针：schema owner 若是当前角色所属组，也视为当前角色（查询层用 pg_has_role 折叠）。 */
    static final class JdbcProbe implements Probe {
        private final JdbcTemplate jdbc;

        JdbcProbe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String productName() throws Exception {
            try (Connection c = jdbc.getDataSource().getConnection()) {
                return c.getMetaData().getDatabaseProductName();
            }
        }

        @Override
        public String currentUser() {
            return jdbc.queryForObject("SELECT current_user", String.class);
        }

        @Override
        public String schemaOwner() {
            List<String> rows = jdbc.queryForList(
                "SELECT CASE WHEN pg_has_role(current_user, n.nspowner, 'MEMBER') THEN current_user::text ELSE r.rolname END "
                    + "FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner WHERE n.nspname = ?",
                String.class, SCHEMA);
            return rows.isEmpty() ? null : rows.get(0);
        }

        @Override
        public List<String> foreignTables() {
            return jdbc.queryForList(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relkind IN ('r','p') AND NOT pg_has_role(current_user, c.relowner, 'MEMBER') "
                    + "ORDER BY c.relname",
                String.class, SCHEMA);
        }
    }
}
