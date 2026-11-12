package com.group9.websearch.seeds;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DatasetSeedPreparerTest {

    @Test
    void rewritesThenDeduplicates() {
        List<String> out = DatasetSeedPreparer.rewriteAndDedup(List.of(
                "https://www.ncbi.nlm.nih.gov/pmc/articles/PMC1/",
                "http://europepmc.org/articles/pmc1?pdf=render", // same article as above
                "",
                "# comment",
                "https://doi.org/10.1/x"));
        assertEquals(List.of("https://pmc.ncbi.nlm.nih.gov/articles/PMC1/", "https://doi.org/10.1/x"), out);
    }

    @Test
    void interleavesRoundRobinByHostPreservingPerHostOrder() {
        List<String> out = DatasetSeedPreparer.interleaveByHost(List.of(
                "https://a.org/1", "https://a.org/2", "https://a.org/3",
                "https://b.org/1", "https://c.org/1", "https://b.org/2"));
        assertEquals(List.of(
                "https://a.org/1", "https://b.org/1", "https://c.org/1",
                "https://a.org/2", "https://b.org/2",
                "https://a.org/3"), out);
    }
}
