package com.wontlost.aster.aiw.pro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /**
     * 对抗输入（+ 后接长数字串、长分组串）上电话正则必须保持线性：以匹配器读取字符的次数衡量工作量，
     * 与机器快慢无关（CI 上按耗时断言会抖动）。改用原子组之前，第一组输入每字符要读 800 次以上，
     * 现在不超过个位数；上限取 50 留足余量，又能稳定抓住回溯失控。
     */
    @Test
    void adversarialInputsStayLinear() {
        Pattern phone = Pattern.compile(LocalRedactor.PHONE);
        String[] inputs = {
            (" +" + "1".repeat(30)).repeat(1000),
            (" +1" + " 1".repeat(40)).repeat(1000),
            (" +1 (" + "2".repeat(4) + ")" + "-3".repeat(30)).repeat(1000),
            ("138 " + "0013 ".repeat(20)).repeat(1000),
        };
        for (String input : inputs) {
            CountingSequence seq = new CountingSequence(input);
            Matcher m = phone.matcher(seq);
            while (m.find()) {
                // 只统计扫描全文的工作量
            }
            double perChar = (double) seq.reads / input.length();
            assertTrue(perChar < 50, input.length() + " 字符平均每字符读取 " + perChar + " 次");
        }
    }

    /** 记录 charAt 调用次数的字符序列：正则引擎的回溯都会体现为重复读取。 */
    private static final class CountingSequence implements CharSequence {
        private final String text;
        private long reads;

        CountingSequence(String text) {
            this.text = text;
        }

        @Override
        public char charAt(int index) {
            reads++;
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
