package com.wontlost.aster.aiw.pro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 代码块感知脱敏：正文脱敏，围栏代码块逐字节保留（ADR 0049 §2.7）。 */
class CodeAwareRedactorTest {
    private static final String KEY = "sk-ant-abcdefghijklmnopqrstu";
    static final String CODE = "```aster\n"
            + "rule effective on 2026-10-20 when amount > 100000000\n"
            + "notify ops@example.com from 10.0.0.1 key " + KEY + "\n"
            + "```\n";

    private final CodeAwareRedactor redactor = new CodeAwareRedactor(new LocalRedactor());

    @Test
    void redactsProseButKeepsFencedCodeByteIdentical() {
        String msg = "Contact ann@example.com for review.\n" + CODE + "Call +64 4 555 0192 by 2026-10-20 done.";
        String out = redactor.redact(msg);
        assertEquals("Contact [email] for review.\n" + CODE + "Call [phone] by 2026-10-20 done.", out);
    }

    @Test
    void tildeFenceWithInfoStringAndCrlfIsKept() {
        String code = "~~~~ aster {x}\r\nd 2026-10-20\r\n~~~~\r\n";
        assertEquals("[email]\r\n" + code, redactor.redact("a@b.io\r\n" + code));
    }

    @Test
    void unterminatedFenceIsCodeToTheEnd() {
        String code = "```\nd 2026-10-20 ann@example.com\nmore 100000000";
        assertEquals("[email]\n" + code, redactor.redact("ann@example.com\n" + code));
    }

    @Test
    void shorterOrDifferentInnerFenceDoesNotCloseTheBlock() {
        String code = "````md\n```\ninner 2026-10-20\n~~~\n```\nstill 100000000\n````\n";
        String out = redactor.redact(code + "after ann@example.com");
        assertEquals(code + "after [email]", out);
    }

    @Test
    void inlineBacktickRunsAndBacktickInfoStringAreProse() {
        assertEquals("x ``` [email]", redactor.redact("x ``` a@b.io"));
        assertEquals("```a`b [email]\n", redactor.redact("```a`b a@b.io\n"));
        assertEquals("    ```\n[email]", redactor.redact("    ```\na@b.io"));
    }

    @Test
    void closingFenceAllowsTrailingSpacesAndLongerRun() {
        String code = "```\n2026-10-20\n`````  \n";
        assertEquals(code + "[email]", redactor.redact(code + "a@b.io"));
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertNull(redactor.redact(null));
        assertEquals("", redactor.redact(""));
        assertTrue(redactor.delegate() instanceof LocalRedactor);
    }
}
