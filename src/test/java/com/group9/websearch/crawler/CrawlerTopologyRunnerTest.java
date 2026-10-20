package com.group9.websearch.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.topology.TopologyBuilder;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the crawler topology is assembled from the genuine StormCrawler native
 * components and the custom PostgreSQL status updater, without submitting to a cluster
 * (no network, DB or AWS needed).
 */
class CrawlerTopologyRunnerTest {

    @Test
    void buildsTopologyWithNativeComponents() {
        CrawlerTopologyRunner runner = new CrawlerTopologyRunner();
        TopologyBuilder builder = runner.buildTopology("config", "sample-seeds.txt");
        StormTopology topology = builder.createTopology();
        assertNotNull(topology);

        Map<String, ?> spouts = topology.get_spouts();
        Map<String, ?> bolts = topology.get_bolts();

        // The seed spout, the frontier spout, and all five processing bolts must be present.
        assertTrue(spouts.containsKey("seeds"), "seed spout missing");
        assertTrue(spouts.containsKey("frontier"), "frontier spout missing");
        assertTrue(bolts.containsKey("partitioner"), "URLPartitionerBolt missing");
        assertTrue(bolts.containsKey("fetch"), "FetcherBolt missing");
        assertTrue(bolts.containsKey("parse"), "JSoupParserBolt missing");
        assertTrue(bolts.containsKey("s3"), "S3RawHtmlBolt missing");
        assertTrue(bolts.containsKey("status"), "PostgresStatusUpdaterBolt missing");

        assertEquals(2, spouts.size());
        assertEquals(5, bolts.size());
    }

    @Test
    void buildTopologyIsRepeatableAndIndependent() {
        CrawlerTopologyRunner runner = new CrawlerTopologyRunner();
        StormTopology a = runner.buildTopology("config", "sample-seeds.txt").createTopology();
        StormTopology b = runner.buildTopology("other", "seeds.txt").createTopology();
        assertEquals(a.get_bolts().keySet(), b.get_bolts().keySet());
    }
}
