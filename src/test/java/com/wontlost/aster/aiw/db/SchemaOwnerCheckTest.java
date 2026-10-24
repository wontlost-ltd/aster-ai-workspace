package com.wontlost.aster.aiw.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class SchemaOwnerCheckTest {

    /** 桩探针：按构造参数回答，并记录是否被查询，用于验证 off / 非 PostgreSQL 的提前返回。 */
    private static final class StubProbe implements SchemaOwnerCheck.Probe {
        final String product; final String owner; final List<String> foreign; boolean queried;
        StubProbe(String product, String owner, List<String> foreign) { this.product = product; this.owner = owner; this.foreign = foreign; }
        @Override public String productName() { return product; }
        @Override public String currentUser() { queried = true; return "aster_ai_user"; }
        @Override public String schemaOwner() { return owner; }
        @Override public List<String> foreignTables() { return foreign; }
    }

    private static final DefaultApplicationArguments NO_ARGS = new DefaultApplicationArguments();

    @Test
    void ownerAndTablesMatchCurrentUserIsOk() {
        var v = SchemaOwnerCheck.evaluate("strict", "aster_ai_user", "aster_ai_user", List.of());
        assertThat(v.ok()).isTrue();
        assertThat(v.fatal()).isFalse();
    }

    @Test
    void missingSchemaBeforeFirstInitIsOk() {
        assertThat(SchemaOwnerCheck.evaluate("strict", "aster_ai_user", null, List.of()).ok()).isTrue();
    }

    @Test
    void strictModeFailsStartupAndNamesObjects() {
        var check = new SchemaOwnerCheck(new StubProbe("PostgreSQL", "postgres", List.of("agui_audit")), "strict");
        assertThatThrownBy(() -> check.run(NO_ARGS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("postgres")
            .hasMessageContaining("agui_audit");
    }

    @Test
    void lenientModeOnlyWarns() {
        var check = new SchemaOwnerCheck(new StubProbe("PostgreSQL", "postgres", List.of()), "lenient");
        assertThatCode(() -> check.run(NO_ARGS)).doesNotThrowAnyException();
    }

    @Test
    void offModeDoesNotTouchTheDatabase() throws Exception {
        var probe = new StubProbe("PostgreSQL", "postgres", List.of("x"));
        new SchemaOwnerCheck(probe, "off").run(NO_ARGS);
        assertThat(probe.queried).isFalse();
    }

    @Test
    void unknownModeIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new SchemaOwnerCheck(new StubProbe("PostgreSQL", null, List.of()), "paranoid"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("paranoid");
    }

    @Test
    void nonPostgresIsSkippedEvenInStrict() throws Exception {
        var probe = new StubProbe("H2", "postgres", List.of("x"));
        new SchemaOwnerCheck(probe, "strict").run(NO_ARGS);
        assertThat(probe.queried).isFalse();
    }
}
