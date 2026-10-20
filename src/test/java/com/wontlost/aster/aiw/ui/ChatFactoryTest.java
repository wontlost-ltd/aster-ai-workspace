package com.wontlost.aster.aiw.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wontlost.agui.AgUiChat;
import com.wontlost.aster.aiw.agent.RunRequestBuilder.Mode;
import com.wontlost.aster.aiw.auth.AiwUser;
import com.wontlost.aster.aiw.auth.UserTicket;
import com.wontlost.aster.aiw.i18n.AiwI18nProvider;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ChatFactoryTest {
    private static final AiwUser ANN = new AiwUser("u1", "Ann", null, "de", "member");
    private final UserTicket tickets = new UserTicket("k", Clock.systemUTC());
    private final ChatFactory factory = new ChatFactory(tickets, new AiwI18nProvider());

    private static String raw(AgUiChat chat, String property) {
        return String.valueOf(chat.getElement().getPropertyRaw(property));
    }

    private static JsonNode props(AgUiChat chat) {
        return (JsonNode) chat.getElement().getPropertyRaw("forwardedProps");
    }

    @Test
    void panelChatOffersInsertAndReplaceAndCarriesTicket() {
        AgUiChat chat = factory.create(ANN, Mode.SUGGEST, "p-1", true);

        assertEquals("/agui/aster", chat.getAgentUrl());
        String actions = raw(chat, "codeActions");
        assertTrue(actions.contains("copy") && actions.contains("insert") && actions.contains("replace"), actions);
        JsonNode props = props(chat);
        assertEquals("suggest", props.path("mode").asString());
        assertEquals("p-1", props.path("policyId").asString());
        assertTrue(tickets.verify(props.path("ticket").asString()).isPresent(), "ticket 必须可被 AsterAgent 校验");
        assertTrue(chat.getThemeNames().contains("compact"));
    }

    @Test
    void workspaceChatOnlyCopiesAndIsNotCompact() {
        AgUiChat chat = factory.create(ANN, Mode.ASK, null, false);

        String actions = raw(chat, "codeActions");
        assertTrue(actions.contains("copy"), actions);
        assertFalse(actions.contains("insert") || actions.contains("replace"), actions);
        assertEquals("ask", props(chat).path("mode").asString());
        assertTrue(props(chat).path("policyId").isMissingNode());
        assertFalse(chat.getThemeNames().contains("compact"));
    }

    @Test
    void switchingModeKeepsTicketAndPolicy() {
        AgUiChat chat = factory.create(ANN, Mode.SUGGEST, "p-1", true);
        String ticket = props(chat).path("ticket").asString();

        ChatFactory.switchMode(chat, Mode.GENERATE);

        assertEquals("generate", props(chat).path("mode").asString());
        assertEquals("p-1", props(chat).path("policyId").asString());
        assertEquals(ticket, props(chat).path("ticket").asString());
    }

    @Test
    void i18nFollowsTheUserLocale() {
        AgUiChat chat = factory.create(ANN, Mode.ASK, null, false);
        String i18n = raw(chat, "i18n");
        assertTrue(i18n.contains("Senden"), i18n);
    }
}
