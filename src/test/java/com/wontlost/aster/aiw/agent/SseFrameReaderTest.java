package com.wontlost.aster.aiw.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SseFrameReaderTest {

    @Test
    void parsesCompleteFramesInOneChunk() {
        var carry = new StringBuilder();
        var frames = SseFrameReader.parseFrames(
                "data:{\"type\":\"meta\",\"data\":\"m\"}\n\ndata:{\"type\":\"upstream_reached\"}\n\n", carry);
        assertEquals(2, frames.size());
        assertEquals("meta", frames.get(0).get("type").asString());
        assertEquals("upstream_reached", frames.get(1).get("type").asString());
        assertEquals("", carry.toString());
    }

    @Test
    void frameSplitAcrossChunksStaysInCarry() {
        var carry = new StringBuilder();
        assertTrue(SseFrameReader.parseFrames("data:{\"type\":\"del", carry).isEmpty());
        assertTrue(SseFrameReader.parseFrames("ta\",\"data\":\"x\"}\n", carry).isEmpty());
        var frames = SseFrameReader.parseFrames("\ndata:{\"type\":\"end\"}", carry);
        assertEquals(1, frames.size());
        assertEquals("x", frames.get(0).get("data").asString());
        assertEquals("data:{\"type\":\"end\"}", carry.toString());
    }

    @Test
    void handlesCrlfSeparators() {
        var carry = new StringBuilder();
        var frames = SseFrameReader.parseFrames(
                "data: {\"type\":\"delta\",\"data\":\"a\"}\r\n\r\ndata: {\"type\":\"delta\",\"data\":\"b\"}\r\n\r\n", carry);
        assertEquals(2, frames.size());
        assertEquals("b", frames.get(1).get("data").asString());
    }

    @Test
    void ignoresEventAndCommentLines() {
        var carry = new StringBuilder();
        var frames = SseFrameReader.parseFrames(
                ": keepalive\nevent: message\nid: 7\ndata:{\"type\":\"delta\",\"data\":\"a\"}\n\n", carry);
        assertEquals(1, frames.size());
        assertEquals("a", frames.get(0).get("data").asString());
    }

    @Test
    void nonJsonPayloadBecomesDelta() {
        var carry = new StringBuilder();
        var frames = SseFrameReader.parseFrames("data:hello world\n\n", carry);
        assertEquals(1, frames.size());
        assertEquals("delta", frames.get(0).get("type").asString());
        assertEquals("hello world", frames.get(0).get("data").asString());
    }

    @Test
    void doneSentinelAndEmptyDataAreDropped() {
        var carry = new StringBuilder();
        assertTrue(SseFrameReader.parseFrames("data:[DONE]\n\n: ping\n\ndata:\n\n", carry).isEmpty());
    }

    @Test
    void multipleDataLinesAreJoinedWithNewline() {
        var carry = new StringBuilder();
        var frames = SseFrameReader.parseFrames("data:line1\ndata:line2\n\n", carry);
        assertEquals("line1\nline2", frames.get(0).get("data").asString());
    }
}
