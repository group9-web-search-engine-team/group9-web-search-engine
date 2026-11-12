package com.group9.websearch.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DatasetUrlRewriterTest {

    @ParameterizedTest
    @CsvSource({
        // Old PMC host (robots.txt: Disallow /pmc/articles/) -> new PMC host
        "https://www.ncbi.nlm.nih.gov/pmc/articles/PMC35282/, https://pmc.ncbi.nlm.nih.gov/articles/PMC35282/",
        "http://www.ncbi.nlm.nih.gov/pmc/articles/pmc7104390, https://pmc.ncbi.nlm.nih.gov/articles/PMC7104390/",
        "https://www.ncbi.nlm.nih.gov/pmc/articles/PMC123/pdf/x.pdf, https://pmc.ncbi.nlm.nih.gov/articles/PMC123/",
        // Old PubMed path -> pubmed host
        "https://www.ncbi.nlm.nih.gov/pubmed/27358972/, https://pubmed.ncbi.nlm.nih.gov/27358972/",
        // Europe PMC PDF render -> PMC HTML article
        "http://europepmc.org/articles/pmc125340?pdf=render, https://pmc.ncbi.nlm.nih.gov/articles/PMC125340/",
        // arXiv PDF -> abstract page
        "https://arxiv.org/pdf/2007.00392v1.pdf, https://arxiv.org/abs/2007.00392v1",
        "https://arxiv.org/pdf/2005.10103v2, https://arxiv.org/abs/2005.10103v2",
        // medRxiv legacy RSS link -> canonical content URL
        "http://medrxiv.org/cgi/content/short/2020.05.10.20088369v1?rss=1, https://www.medrxiv.org/content/10.1101/2020.05.10.20088369v1",
    })
    void rewritesKnownDatasetShapes(String in, String expected) {
        assertEquals(expected, DatasetUrlRewriter.rewrite(in));
    }

    @ParameterizedTest
    @CsvSource({
        "https://doi.org/10.1016/j.jcv.2020.104357",
        "https://pmc.ncbi.nlm.nih.gov/articles/PMC35282/",
        "https://www.ncbi.nlm.nih.gov/books/NBK1/",
        "https://example.com/",
    })
    void leavesOtherUrlsUnchanged(String url) {
        assertEquals(url, DatasetUrlRewriter.rewrite(url));
    }

    @Test
    void nullIsNull() {
        assertNull(DatasetUrlRewriter.rewrite(null));
    }

    @Test
    void filterDelegatesToRewriter() {
        assertEquals("https://pmc.ncbi.nlm.nih.gov/articles/PMC1/",
                new DatasetUrlRewriteFilter().filter(null, null,
                        "https://www.ncbi.nlm.nih.gov/pmc/articles/PMC1/"));
    }
}
