package com.group9.websearch.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CrawlModeTest {

    @Test
    void defaultIsResumeAndNeverResets() {
        assertEquals(CrawlMode.RESUME, CrawlMode.DEFAULT);
        assertEquals(CrawlMode.RESUME, CrawlMode.parse(null));
        assertEquals(CrawlMode.RESUME, CrawlMode.parse(""));
        assertEquals(CrawlMode.RESUME, CrawlMode.parse("  "));
    }

    @Test
    void parsesResume() {
        assertEquals(CrawlMode.RESUME, CrawlMode.parse("resume"));
        assertEquals(CrawlMode.RESUME, CrawlMode.parse("RESUME"));
    }

    @Test
    void parsesCleanStartVariants() {
        assertEquals(CrawlMode.CLEAN_START, CrawlMode.parse("clean-start"));
        assertEquals(CrawlMode.CLEAN_START, CrawlMode.parse("CLEAN-START"));
        assertEquals(CrawlMode.CLEAN_START, CrawlMode.parse("clean_start"));
        assertEquals(CrawlMode.CLEAN_START, CrawlMode.parse("cleanstart"));
    }

    @Test
    void rejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> CrawlMode.parse("wipe"));
    }
}
