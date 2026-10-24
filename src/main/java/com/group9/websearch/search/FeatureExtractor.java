package com.group9.websearch.search;

import com.group9.websearch.index.HitType;
import com.group9.websearch.index.Posting;
import com.group9.websearch.index.PostingList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts real, field-specific ranking features for a candidate document from the positional
 * postings that matched the query. Feature extraction is isolated from scoring: it only
 * measures signals, it does not combine them.
 *
 * <p>All signals come from data genuinely present in the index:
 * <ul>
 *   <li>Field matches (title/url/anchor/body): counted from per-field positions in each term's
 *       posting for the document. Anchor is naturally 0 while anchor text is not indexed.</li>
 *   <li>Coverage: fraction of query terms hitting the title / the document at all.</li>
 *   <li>Body term frequency: log-dampened sum of body position counts across query terms.</li>
 *   <li>Proximity: derived from the minimum span covering one occurrence of every query term in
 *       a shared field; mapped to 0..1 (1 = adjacent/phrase-tight, decaying with span). Only
 *       meaningful for multi-term queries; single-term queries get a neutral 0.</li>
 *   <li>PageRank: only if a non-null value is supplied by the caller (metadata source); the
 *       crawler slice does not populate it, so it is normally absent (0).</li>
 * </ul>
 */
public final class FeatureExtractor {

    /** Optional per-document PageRank supplier; returns null when unavailable (never fabricated). */
    public interface PageRankSource {
        Double pageRank(long docId);
    }

    private final PageRankSource pageRankSource;

    public FeatureExtractor(PageRankSource pageRankSource) {
        this.pageRankSource = pageRankSource;
    }

    public FeatureExtractor() {
        this(docId -> null);
    }

    /**
     * Build the feature vector for {@code docId} given the query terms and the postings each
     * known query term produced (term -> postings).
     */
    public FeatureVector extract(long docId, List<String> queryTerms,
            Map<String, PostingList> termPostings) {
        int totalQueryTerms = Math.max(1, queryTerms.size());

        int titleMatches = 0;
        int urlMatches = 0;
        int anchorMatches = 0;
        int bodyMatches = 0;
        int termsPresentAnywhere = 0;
        long bodyPositionCount = 0;

        // Collect, per term, the body positions for this doc so we can compute proximity.
        List<List<Integer>> bodyPositionsPerTerm = new ArrayList<>();

        for (String term : queryTerms) {
            PostingList pl = termPostings.get(term);
            if (pl == null) {
                continue;
            }
            Posting p = postingFor(pl, docId);
            if (p == null) {
                continue;
            }
            boolean present = false;
            if (p.termFrequency(HitType.TITLE) > 0) {
                titleMatches++;
                present = true;
            }
            if (p.termFrequency(HitType.URL) > 0) {
                urlMatches++;
                present = true;
            }
            if (p.termFrequency(HitType.ANCHOR) > 0) {
                anchorMatches++;
                present = true;
            }
            int bodyTf = p.termFrequency(HitType.BODY);
            if (bodyTf > 0) {
                bodyMatches++;
                present = true;
                bodyPositionCount += bodyTf;
                bodyPositionsPerTerm.add(p.positions(HitType.BODY));
            }
            if (present) {
                termsPresentAnywhere++;
            }
        }

        double titleCoverage = titleMatches / (double) totalQueryTerms;
        double queryCoverage = termsPresentAnywhere / (double) totalQueryTerms;
        double bodyTf = bodyPositionCount == 0 ? 0.0 : Math.log1p(bodyPositionCount);
        double proximity = proximityScore(queryTerms.size(), bodyPositionsPerTerm);

        FeatureVector.Builder b = FeatureVector.builder()
                .set(Feature.TITLE_MATCHES, titleMatches)
                .set(Feature.URL_MATCHES, urlMatches)
                .set(Feature.ANCHOR_MATCHES, anchorMatches)
                .set(Feature.BODY_MATCHES, bodyMatches)
                .set(Feature.TITLE_COVERAGE, titleCoverage)
                .set(Feature.QUERY_COVERAGE, queryCoverage)
                .set(Feature.BODY_TF, bodyTf)
                .set(Feature.PROXIMITY, proximity);

        Double pr = pageRankSource == null ? null : pageRankSource.pageRank(docId);
        if (pr != null) {
            b.set(Feature.PAGERANK, pr);
        }
        return b.build();
    }

    /**
     * Map the tightest body span covering one occurrence of every query term to a 0..1 score.
     * Only applies when all query terms have body positions (a true multi-term proximity). The
     * score is {@code (window/(window+span))}-style decay: span 0 -> ~1, large span -> ~0.
     */
    private double proximityScore(int numQueryTerms, List<List<Integer>> bodyPositionsPerTerm) {
        if (numQueryTerms < 2 || bodyPositionsPerTerm.size() < numQueryTerms) {
            return 0.0; // single-term or not all terms in body -> no proximity contribution
        }
        int span = PostingOps.minSpan(bodyPositionsPerTerm);
        if (span == Integer.MAX_VALUE) {
            return 0.0;
        }
        // Ideal minimum span for n adjacent terms is (n-1); normalise against it.
        int ideal = Math.max(1, numQueryTerms - 1);
        return (double) ideal / (ideal + span);
    }

    private static Posting postingFor(PostingList pl, long docId) {
        for (Posting p : pl.postings()) {
            if (p.docId() == docId) {
                return p;
            }
        }
        return null;
    }
}
