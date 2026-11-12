package com.group9.websearch.crawler;

import java.net.URL;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.filtering.URLFilter;

/**
 * StormCrawler {@link URLFilter} that applies {@link DatasetUrlRewriter} to every outlink and
 * redirect target before it reaches the frontier, so links to the stale CORD-19 URL shapes
 * (e.g. {@code www.ncbi.nlm.nih.gov/pmc/articles/...}, which robots.txt now disallows) are
 * turned into their crawlable canonical form instead of being dropped.
 *
 * <p>Register it FIRST in {@code urlfilters.json} so the normalizer/depth/host filters see
 * the rewritten URL. It never rejects a URL (never returns null for non-null input).
 */
public class DatasetUrlRewriteFilter extends URLFilter {

    @Override
    public String filter(URL sourceUrl, Metadata sourceMetadata, String urlToFilter) {
        return DatasetUrlRewriter.rewrite(urlToFilter);
    }
}
