package com.wontlost.aster.aiw.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class UserTicketTest {
    private static final long NOW = 1_800_000_000L;
    private static final AiwUser ANN = new AiwUser("user-1", "Ann", "ann@example.com", "de", "owner");

    private static UserTicket at(long epochSecond) {
        return new UserTicket("ticket-key", Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    @Test
    void issueThenVerifyRoundTrips() {
        String ticket = at(NOW).issue(ANN);
        assertEquals(new TicketUser("user-1", "de"), at(NOW + 60).verify(ticket).orElseThrow());
    }

    @Test
    void payloadIsUserLocaleExpWithTwelveHourTtl() {
        String ticket = at(NOW).issue(ANN);
        String payload = new String(
                Base64.getUrlDecoder().decode(ticket.substring(0, ticket.indexOf('.'))), StandardCharsets.UTF_8);
        assertEquals("user-1|de|" + (NOW + 12 * 3600), payload);
        assertTrue(ticket.substring(ticket.indexOf('.') + 1).matches("[0-9a-f]{64}"));
    }

    @Test
    void tamperedTicketIsRejected() {
        String ticket = at(NOW).issue(ANN);
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("user-2|de|" + (NOW + 12 * 3600)).getBytes(StandardCharsets.UTF_8));
        assertTrue(at(NOW).verify(forgedPayload + ticket.substring(ticket.indexOf('.'))).isEmpty());
        char last = ticket.charAt(ticket.length() - 1);
        String flipped = ticket.substring(0, ticket.length() - 1) + (last == '0' ? '1' : '0');
        assertTrue(at(NOW).verify(flipped).isEmpty());
    }

    @Test
    void ticketFromAnotherKeyIsRejected() {
        var other = new UserTicket("other-key", Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
        assertTrue(at(NOW).verify(other.issue(ANN)).isEmpty());
    }

    @Test
    void expiredTicketIsRejected() {
        String ticket = at(NOW).issue(ANN);
        assertTrue(at(NOW + 12 * 3600).verify(ticket).isPresent());
        assertTrue(at(NOW + 12 * 3600 + 1).verify(ticket).isEmpty());
    }

    @Test
    void malformedTicketsNeverThrow() {
        var t = at(NOW);
        for (String bad : new String[] {null, "", ".", "abc", "a.b.c", "!!!.zz", "dXNlcg.00"}) {
            assertTrue(t.verify(bad).isEmpty(), "应拒绝: " + bad);
        }
    }

    @Test
    void blankKeyVerifiesNothingAndRefusesToIssue() {
        var blank = new UserTicket(" ", Clock.systemUTC());
        assertTrue(blank.verify(at(NOW).issue(ANN)).isEmpty());
        assertThrows(IllegalStateException.class, () -> blank.issue(ANN));
        assertTrue(new UserTicket(null, Clock.systemUTC()).verify("a.b").isEmpty());
    }

    @Test
    void isTheTicketVerifierUsedByAsterAgent() {
        TicketVerifier v = at(NOW);
        assertTrue(v.verify(at(NOW).issue(ANN)).isPresent());
    }
}
