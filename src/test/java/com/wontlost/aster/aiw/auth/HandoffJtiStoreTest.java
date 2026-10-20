package com.wontlost.aster.aiw.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class HandoffJtiStoreTest {
    private final JdbcTemplate jdbc = H2.fresh();
    private final HandoffJtiStore store = new HandoffJtiStore(jdbc);

    @Test
    void firstClaimWinsAndReplayLoses() {
        Instant exp = Instant.now().plusSeconds(60);
        assertTrue(store.claim("jti-1", exp));
        assertFalse(store.claim("jti-1", exp));
        assertTrue(store.claim("jti-2", exp));
    }

    @Test
    void purgeRemovesOnlyExpired() {
        store.claim("old", Instant.now().minus(Duration.ofMinutes(5)));
        store.claim("live", Instant.now().plus(Duration.ofMinutes(5)));
        store.purgeExpired();
        assertEquals(1, jdbc.queryForObject("select count(*) from aiw_handoff_jti", Integer.class));
        assertEquals("live", jdbc.queryForObject("select jti from aiw_handoff_jti", String.class));
    }

    private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);
    private final FlakyJdbc flaky = H2.flaky();
    private final HandoffJtiStore fixed = new HandoffJtiStore(flaky, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void databaseOutageFallsBackToSingleUseMemoryRegistry() {
        flaky.down = true;
        assertTrue(fixed.claim("j-out", NOW.plusSeconds(65)));
        assertFalse(fixed.claim("j-out", NOW.plusSeconds(65)));
        assertTrue(fixed.claim("j-out-2", NOW.plusSeconds(65)));
    }

    @Test
    void jtiClaimedDuringOutageCannotBeReplayedAfterRecovery() {
        flaky.down = true;
        assertTrue(fixed.claim("j-out", NOW.plusSeconds(65)));
        flaky.down = false;
        assertFalse(fixed.claim("j-out", NOW.plusSeconds(65)));
        assertEquals(0, flaky.queryForObject("select count(*) from aiw_handoff_jti", Integer.class));
        assertTrue(fixed.claim("j-new", NOW.plusSeconds(65)));
    }

    @Test
    void memoryRegistryIsCappedAndEvictsExpiredFirst() {
        flaky.down = true;
        for (int i = 0; i < HandoffJtiStore.MEMORY_CAP - 1; i++) {
            fixed.claim("old-" + i, NOW.minusSeconds(1));
        }
        assertTrue(fixed.claim("live-0", NOW.plusSeconds(65)));
        assertTrue(fixed.claim("live-1", NOW.plusSeconds(65)), "满额时先清过期项");
        assertEquals(2, fixed.memorySize());

        for (int i = 2; i < HandoffJtiStore.MEMORY_CAP; i++) {
            fixed.claim("live-" + i, NOW.plusSeconds(65));
        }
        assertEquals(HandoffJtiStore.MEMORY_CAP, fixed.memorySize());
        assertFalse(fixed.claim("overflow", NOW.plusSeconds(65)), "全为未过期项时按重放拒绝");
    }

    @Test
    void scheduledPurgeClearsExpiredMemoryEntriesAndSurvivesOutage() {
        flaky.down = true;
        fixed.claim("gone", NOW.minusSeconds(1));
        fixed.claim("kept", NOW.plusSeconds(65));
        fixed.purgeExpired();
        assertEquals(1, fixed.memorySize());
    }
}
