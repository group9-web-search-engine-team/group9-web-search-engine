package com.group9.websearch.crawler;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group9.websearch.crawler.persistence.FrontierRepository;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class CrawlModeManagerTest {

    @Test
    void cleanStartResetsTablesAndDoesNotRecover() throws Exception {
        FrontierRepository repo = mock(FrontierRepository.class);
        when(repo.resetCrawlTables()).thenReturn(new int[] {193, 9, 224});

        String summary = new CrawlModeManager(repo).apply(CrawlMode.CLEAN_START);

        verify(repo, times(1)).resetCrawlTables();
        verify(repo, never()).recoverStaleInProgress();
        assertTrue(summary.contains("CLEAN_START"));
        assertTrue(summary.contains("S3 objects preserved"));
    }

    @Test
    void resumeRecoversStaleInProgressAndDoesNotReset() throws Exception {
        FrontierRepository repo = mock(FrontierRepository.class);
        when(repo.recoverStaleInProgress()).thenReturn(4);

        String summary = new CrawlModeManager(repo).apply(CrawlMode.RESUME);

        verify(repo, times(1)).recoverStaleInProgress();
        verify(repo, never()).resetCrawlTables();
        assertTrue(summary.contains("RESUME"));
        assertTrue(summary.contains("recovered 4"));
    }

    @Test
    void cleanStartPropagatesSqlErrors() throws Exception {
        FrontierRepository repo = mock(FrontierRepository.class);
        when(repo.resetCrawlTables()).thenThrow(new SQLException("permission denied for table links"));
        assertThrows(SQLException.class, () -> new CrawlModeManager(repo).apply(CrawlMode.CLEAN_START));
    }
}
