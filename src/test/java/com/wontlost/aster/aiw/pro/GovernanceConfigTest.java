package com.wontlost.aster.aiw.pro;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wontlost.agui.ee.governance.PatternRedactor;
import com.wontlost.agui.ee.governance.Redactor;
import com.wontlost.agui.ee.governance.RetentionPolicy;
import com.wontlost.agui.pro.license.License;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 治理装配（ADR 0049 §2.7）：脱敏（含类 Key 模式）、180 天保留、定时任务。
 * 未授权时绝不触达 Pro 的 License.require：这里把试用期置为已过期，一旦触达即抛 LicenseException。
 */
class GovernanceConfigTest {
    private static final String KEY = "sk-ant-abcdefghijklmnopqrstu";

    @TempDir Path home;

    private ApplicationContextRunner runner(boolean licensed) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.wontlost.agui.ee.governance.GovernanceAutoConfiguration.class))
                .withUserConfiguration(GovernanceConfig.class)
                .withBean(ProFeatures.class, () -> new ProFeatures(f -> licensed))
                .withPropertyValues(
                        "agui.ee.governance.retention.conversations-days=180",
                        "agui.ee.governance.retention.audit-days=180");
    }

    @BeforeEach
    void expiredTrial() throws Exception {
        System.setProperty(License.HOME_PROPERTY, home.toString());
        License.configure(null);
        Path trial = home.resolve(".agui-pro/trial");
        Files.createDirectories(trial.getParent());
        Files.writeString(trial, "2000-01-01");
    }

    @AfterEach
    void restore() {
        System.clearProperty(License.HOME_PROPERTY);
        License.configure(null);
    }

    @Test
    void unlicensedStillRedactsWithoutTouchingTheProLicenseGate() {
        runner(false).run(ctx -> {
            assertFalse(ctx.getStartupFailure() != null, "未授权不得因 License.require 启动失败");
            Redactor r = ctx.getBean(Redactor.class);
            assertInstanceOf(LocalRedactor.class, assertInstanceOf(CodeAwareRedactor.class, r).delegate());
            assertEquals("k=[key]", r.redact("k=" + KEY));
            assertEquals("[email]", r.redact("ann@example.com"));
            assertEquals("ip [ip]", r.redact("ip 10.0.0.1"));
            assertEquals("card [card]", r.redact("card 4111 1111 1111 1111"));
            assertEquals("order 1234567890123", r.redact("order 1234567890123"));
        });
    }

    @Test
    void unlicensedNeverBuildsTheRetentionService() {
        runner(false).run(ctx -> {
            assertDoesNotThrow(() -> ctx.getBean(RetentionJob.class).run());
            assertTrue(ctx.getBean(RetentionJob.class).lastRun().isEmpty());
        });
    }

    @Test
    void licensedUsesProPatternRedactorWithApiKeyPattern() throws Exception {
        Files.writeString(home.resolve(".agui-pro/trial"), java.time.LocalDate.now().toString());
        runner(true).run(ctx -> {
            Redactor r = ctx.getBean(Redactor.class);
            assertInstanceOf(PatternRedactor.class, assertInstanceOf(CodeAwareRedactor.class, r).delegate());
            assertEquals("[key]", r.redact(KEY));
            assertEquals("[email]", r.redact("ann@example.com"));
            assertPhoneNeedsStructure(r);
        });
    }

    @Test
    void unlicensedPhoneRuleNeedsStructure() {
        runner(false).run(ctx -> assertPhoneNeedsStructure(ctx.getBean(Redactor.class)));
    }

    /** 已授权与未授权两条路径的电话判定一致：金额、日期不是电话（D5）。 */
    private static void assertPhoneNeedsStructure(Redactor r) {
        assertEquals("max 999999999 / 1,000,000 / 10000 on 2026-10-22",
                r.redact("max 999999999 / 1,000,000 / 10000 on 2026-10-22"));
        assertEquals("tel [phone] or [phone]", r.redact("tel +86 138 0013 8000 or 13800138000"));
    }

    @Test
    void licensedRedactsProseButKeepsFencedCodeIntact() throws Exception {
        Files.writeString(home.resolve(".agui-pro/trial"), java.time.LocalDate.now().toString());
        runner(true).run(ctx -> assertCodeKept(ctx.getBean(Redactor.class)));
    }

    @Test
    void unlicensedRedactsProseButKeepsFencedCodeIntact() {
        runner(false).run(ctx -> assertCodeKept(ctx.getBean(Redactor.class)));
    }

    private static void assertCodeKept(Redactor r) {
        String msg = "Mail ann@example.com\n" + CodeAwareRedactorTest.CODE + "tail " + KEY;
        assertEquals("Mail [email]\n" + CodeAwareRedactorTest.CODE + "tail [key]", r.redact(msg));
    }

    @Test
    void retentionIs180DaysAndTheJobRunsWhenLicensed() throws Exception {
        Files.writeString(home.resolve(".agui-pro/trial"), java.time.LocalDate.now().toString());
        runner(true).run(ctx -> {
            RetentionPolicy.Periods p = ctx.getBean(RetentionPolicy.class).defaults();
            assertEquals(Duration.ofDays(180), p.conversations());
            assertEquals(Duration.ofDays(180), p.audit());
            assertDoesNotThrow(() -> ctx.getBean(RetentionJob.class).run());
            assertTrue(ctx.getBean(RetentionJob.class).lastRun().isPresent());
        });
    }
}
