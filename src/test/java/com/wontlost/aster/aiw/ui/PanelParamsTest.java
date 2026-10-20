package com.wontlost.aster.aiw.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PanelParamsTest {
    @Test
    void validPolicyIdIsKept() {
        assertEquals("p_1-A", PanelParams.policyId(Optional.of("p_1-A")));
        assertEquals("policy:p_1-A", PanelParams.threadId("p_1-A"));
    }

    @Test
    void missingOrInvalidPolicyIdBecomesNew() {
        assertEquals("new", PanelParams.policyId(Optional.empty()));
        assertEquals("new", PanelParams.policyId(Optional.of("")));
        assertEquals("new", PanelParams.policyId(Optional.of("a/b")));
        assertEquals("new", PanelParams.policyId(Optional.of("x".repeat(65))));
        assertEquals("policy:new", PanelParams.threadId("new"));
    }
}
