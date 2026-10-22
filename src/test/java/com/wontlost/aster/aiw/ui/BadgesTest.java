package com.wontlost.aster.aiw.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.flow.component.ComponentUtil;
import com.wontlost.agui.AgUiChat;
import com.wontlost.aster.aiw.agent.RunRequestBuilder.Mode;
import com.wontlost.aster.aiw.i18n.AiwI18nProvider;
import com.wontlost.aster.aiw.ui.Badge.Tone;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 徽标色调映射与分段控件外观钩子：只验证样式所依赖的类名 / 属性，行为由 ViewsTest 覆盖。 */
class BadgesTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final AiwI18nProvider i18n = new AiwI18nProvider();
    private final Function<String, String> en = key -> i18n.text("en", key);
    private final AgUiChat chat = new AgUiChat("/agui/test");

    private void state(String json) {
        ComponentUtil.fireEvent(chat, new AgUiChat.StateChangedEvent(chat, true, (ObjectNode) MAPPER.readTree(json)));
    }

    @Test
    void validatedIsSuccess() {
        StatusLabel label = new StatusLabel(chat, en);
        state("{\"validated\":true}");
        assertTrue(label.isVisible());
        assertEquals(Tone.SUCCESS, label.getTone());
        assertEquals("success", label.getElement().getAttribute("data-tone"));
        assertEquals("✓ Validated", label.getText());
    }

    @Test
    void notValidatedIsDangerAndKeepsTheFullTextAsTooltip() {
        StatusLabel label = new StatusLabel(chat, en);
        state("{\"validated\":false,\"validationError\":\"line 3: expected produce\"}");
        assertEquals(Tone.DANGER, label.getTone());
        assertEquals("✗ Not validated: line 3: expected produce", label.getText());
        assertEquals(label.getText(), label.getTitle().orElseThrow());
    }

    @Test
    void repairingIsPrimary() {
        StatusLabel label = new StatusLabel(chat, en);
        state("{\"validationError\":\"bad\",\"repair\":\"2/3\"}");
        assertEquals(Tone.PRIMARY, label.getTone());
        assertEquals("Repairing (2/3)…", label.getText());
    }

    @Test
    void toneFollowsTheLatestStateAndNothingToShowHides() {
        StatusLabel label = new StatusLabel(chat, en);
        state("{\"repair\":\"2/3\"}");
        state("{\"validated\":true,\"repair\":\"2/3\"}");
        assertEquals(Tone.SUCCESS, label.getTone(), "校验结论优先于修复进度");
        state("{\"validated\":null,\"validationError\":null,\"repair\":null}");
        assertFalse(label.isVisible());
    }

    @Test
    void describeReturnsNullWhenThereIsNothingToShow() {
        StatusLabel label = new StatusLabel(chat, en);
        assertNull(label.describe(MAPPER.readTree("{\"model\":\"m\"}")));
    }

    @Test
    void truncatedIsAWarningBadgeInsideAPermanentLiveRegion() {
        TruncatedLabel label = new TruncatedLabel(chat, en.apply(TruncatedLabel.KEY));
        assertTrue(label.isVisible(), "live region 常驻，不用 hidden 切换");
        assertEquals("status", label.getElement().getAttribute("role"));
        assertFalse(label.showing());
        assertEquals(0, label.getElement().getChildCount());

        state("{\"truncated\":true}");
        assertTrue(label.showing());
        assertEquals(Tone.WARNING, label.badge().getTone());
        assertEquals("warning", label.badge().getElement().getAttribute("data-tone"));
        assertEquals("Answer was truncated", label.badge().getText());
        state("{\"truncated\":true}");
        assertEquals(1, label.getElement().getChildCount(), "重复状态不重复插入");

        state("{\"truncated\":null}");
        assertFalse(label.showing());
        state("{\"truncated\":true}");
        ComponentUtil.fireEvent(chat, new AgUiChat.RunStartedEvent(chat, true, "t", "r2"));
        assertFalse(label.showing());
        assertTrue(label.isVisible());
    }

    @Test
    void modelIsANeutralBadge() {
        ModelLabel label = new ModelLabel(chat, en.apply(ModelLabel.KEY));
        state("{\"model\":\"test-model\"}");
        assertTrue(label.hasClassName("aster-badge"));
        assertEquals(Tone.NEUTRAL, label.getTone());
        assertEquals("Model: test-model", label.getText());
    }

    @Test
    void modeSelectorIsASegmentedControlAndCompactOnlyWhenAsked() {
        ModeSelector workspace = new ModeSelector(Mode.ASK, en);
        ModeSelector panel = new ModeSelector(Mode.SUGGEST, en).compact();
        assertTrue(workspace.getContent().hasClassName("aster-segmented"));
        assertFalse(workspace.getContent().hasClassName("aster-compact"));
        assertTrue(panel.getContent().hasClassName("aster-segmented"));
        assertTrue(panel.getContent().hasClassName("aster-compact"));
        assertEquals(Mode.SUGGEST, panel.getMode());
    }
}
