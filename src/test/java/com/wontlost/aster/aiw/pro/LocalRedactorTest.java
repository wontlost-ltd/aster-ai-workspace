package com.wontlost.aster.aiw.pro;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 电话脱敏表驱动用例：要求电话结构，金额、日期、裸数字串原样保留（D5）。 */
class LocalRedactorTest {
    private final LocalRedactor redactor = new LocalRedactor();

    @ParameterizedTest
    @ValueSource(strings = {
        "+86 138 0013 8000",
        "+8613800138000",
        "+1 (555) 123-4567",
        "+64 4 555 0192",
        "+49 30 901820",
        "+49 30 12345678",
        "+91-98765-43210",
        "+91 98765 43210",
        "98765 43210",
        "030 12345678",
        "0171 1234567",
        "07700 900123",
        "+1 2 3 4 5 6 7",
        "13800138000",
        "138 0013 8000",
        "138-0013-8000",
        "(04) 555-0192",
        "(555) 123-4567",
        "021 123 4567",
        "010-6552-9988",
        "555-123-4567",
        "555.123.4567",
    })
    void phoneLikeStructuresAreRedacted(String phone) {
        assertEquals("[phone]", redactor.redact(phone));
        assertEquals("致电 [phone] 联系", redactor.redact("致电 " + phone + " 联系"));
        assertEquals("call [phone].", redactor.redact("call " + phone + "."));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "999999999",
        "10000",
        "100000000",
        "1,000,000",
        "1,000,000.50",
        "10,00,000",
        "1.000.000,00",
        "1 000 000",
        "12345678",
        "123456789012",
        "2026-10-22",
        "2026/10/22",
        "20261022",
        "3.14159265",
        "0.000012345",
        "v1.2.3",
        "12:30-14:45",
        "138001380001",
        "23800138000",
        "x13800138000",
        "1555-123-4567",
        "+5",
        "+1 23",
        "+12345678",
        "+12345678 bytes",
        "+1 2 3 4 5 6 7 8 9",
        "5551234567",
        "9876543210",
        "12345 67890",
        "order 1234567890123",
    })
    void amountsDatesAndPlainDigitRunsAreKept(String text) {
        assertEquals(text, redactor.redact(text));
        assertEquals("金额 " + text + " 元", redactor.redact("金额 " + text + " 元"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "上限 999999999，生效日 2026-10-22，电话 13800138000 | 上限 999999999，生效日 2026-10-22，电话 [phone]",
        "Pay 1,000,000 by 2026-10-22 or call +86 138 0013 8000 | Pay 1,000,000 by 2026-10-22 or call [phone]",
        "card 4111 1111 1111 1111, ip 10.0.0.1 | card [card], ip [ip]",
        "IBAN DE89 3704 0044 0532 0130 00 | IBAN [iban]",
    })
    void mixedProseOnlyLosesTheRealIdentifiers(String in, String out) {
        assertEquals(out, redactor.redact(in));
    }

    /** 对抗输入（+ 后接长数字串、长分组串）必须线性完成：原子组之前 96 KB 输入要 1 秒以上。 */
    @Test
    void adversarialInputsFinishQuickly() {
        String[] inputs = {
            (" +" + "1".repeat(30)).repeat(3000),
            (" +1" + " 1".repeat(40)).repeat(1000),
            (" +1 (" + "2".repeat(4) + ")" + "-3".repeat(30)).repeat(1000),
            ("138 " + "0013 ".repeat(20)).repeat(1000),
        };
        for (String input : inputs) {
            redactor.redact(input);
        }
        for (String input : inputs) {
            long start = System.nanoTime();
            redactor.redact(input);
            long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
            org.junit.jupiter.api.Assertions.assertTrue(ms < 100, input.length() + " 字符耗时 " + ms + " ms");
        }
    }
}
