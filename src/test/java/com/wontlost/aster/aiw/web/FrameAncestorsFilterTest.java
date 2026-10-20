package com.wontlost.aster.aiw.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FrameAncestorsFilterTest {
    private static final String ORIGIN = "https://aster-lang.cloud";

    @Test
    void setsFrameAncestorsToCloudOriginAndDropsDeny() throws Exception {
        var filter = new FrameAncestorsFilter(ORIGIN);
        var req = new MockHttpServletRequest("GET", "/panel");
        var res = new MockHttpServletResponse();
        res.setHeader("X-Frame-Options", "DENY");
        filter.doFilter(req, res, (rq, rs) -> {});
        assertEquals("frame-ancestors " + ORIGIN, res.getHeader("Content-Security-Policy"));
        assertNull(res.getHeader("X-Frame-Options"));
    }

    /** 真实场景：下游（Vaadin/Spring Security）在链内写入 X-Frame-Options 与自己的 CSP。 */
    @Test
    void overridesHeadersWrittenInsideChain() throws Exception {
        var filter = new FrameAncestorsFilter(ORIGIN);
        var req = new MockHttpServletRequest("GET", "/panel");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> {
            var out = (jakarta.servlet.http.HttpServletResponse) rs;
            out.setHeader("X-Frame-Options", "DENY");
            out.addHeader("x-frame-options", "SAMEORIGIN");
            out.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        });
        assertEquals("frame-ancestors " + ORIGIN, res.getHeader("Content-Security-Policy"));
        assertEquals(1, res.getHeaders("Content-Security-Policy").size());
        assertNull(res.getHeader("X-Frame-Options"));
    }
}
