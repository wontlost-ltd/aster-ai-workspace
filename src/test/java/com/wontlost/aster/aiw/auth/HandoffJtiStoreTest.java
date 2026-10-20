package com.wontlost.aster.aiw.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
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
}
