package com.group9.websearch.search;

import com.group9.websearch.index.PostingList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Sparse candidate generation over the positional inverted index.
 *
 * <p>Given a parsed {@link Query}, it produces the candidate docId set and the per-term postings
 * those candidates matched (handed to the feature extractor). Retrieval is deliberately kept
 * separate from ranking: this class decides <em>which</em> documents are candidates, not how
 * they are ordered.
 *
 * <ul>
 *   <li>OR: union of the postings of all known terms (ordinary-query default).</li>
 *   <li>AND: intersection of all known terms' postings.</li>
 *   <li>NOT: (positive terms, OR/AND) minus excluded terms, constrained to the document universe
 *       when one is available; without a universe it falls back to positive-term candidates
 *       minus excluded-term docs.</li>
 *   <li>PROXIMITY: documents where all terms occur within the query window in some field.</li>
 * </ul>
 *
 * <p>Unknown terms (absent from the index) are dropped from OR, make AND/PROXIMITY empty, and in
 * NOT are simply ignored for exclusion. A query with no known positive terms yields no
 * candidates (handled gracefully, no exception).
 */
public final class RetrievalEngine {

    private final InvertedIndex index;
    private final DocumentStore documents;

    public RetrievalEngine(InvertedIndex index, DocumentStore documents) {
        this.index = index;
        this.documents = documents;
    }

    /** Result of retrieval: candidate docIds plus the postings per matched query term. */
    public static final class Candidates {
        public final Set<Long> docIds;
        /** term -> postings (only for terms present in the index), used for feature extraction. */
        public final Map<String, PostingList> termPostings;
        public final List<String> knownTerms;
        public final List<String> unknownTerms;

        Candidates(Set<Long> docIds, Map<String, PostingList> termPostings,
                List<String> knownTerms, List<String> unknownTerms) {
            this.docIds = docIds;
            this.termPostings = termPostings;
            this.knownTerms = knownTerms;
            this.unknownTerms = unknownTerms;
        }
    }

    public Candidates retrieve(Query query) {
        Map<String, PostingList> termPostings = new LinkedHashMap<>();
        List<String> known = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String term : query.terms()) {
            PostingList pl = index.postings(term);
            if (pl != null && !pl.isEmpty()) {
                termPostings.put(term, pl);
                known.add(term);
            } else {
                unknown.add(term);
            }
        }

        Set<Long> docIds = switch (query.mode()) {
            case OR -> PostingOps.unionDocIds(new ArrayList<>(termPostings.values()));
            case AND -> PostingOps.intersectDocIds(postingsFor(query.terms()));
            case PROXIMITY -> PostingOps.proximityDocIds(postingsFor(query.terms()), query.proximityWindow());
            case NOT -> retrieveNot(query, termPostings);
        };

        return new Candidates(docIds, termPostings, known, unknown);
    }

    /**
     * NOT retrieval: positive candidates (OR of the positive terms) restricted to the known
     * universe, minus any document matching an excluded term. If a document universe exists we
     * start from it so excluding terms is meaningful even for sparse positive matches.
     */
    private Set<Long> retrieveNot(Query query, Map<String, PostingList> positivePostings) {
        Set<Long> base;
        if (query.terms().isEmpty()) {
            // "NOT: -spam" with no positive terms -> everything in the universe minus excluded.
            base = new TreeSet<>(documents.universe());
        } else {
            base = PostingOps.unionDocIds(new ArrayList<>(positivePostings.values()));
        }
        Set<Long> excluded = new TreeSet<>();
        for (String ex : query.excludedTerms()) {
            PostingList pl = index.postings(ex);
            if (pl != null) {
                for (var p : pl.postings()) {
                    excluded.add(p.docId());
                }
            }
        }
        return PostingOps.difference(base, excluded);
    }

    /** Postings for an ordered term list (unknown terms map to null, preserved for AND/prox). */
    private List<PostingList> postingsFor(List<String> terms) {
        List<PostingList> out = new ArrayList<>(terms.size());
        for (String t : terms) {
            out.add(index.postings(t));
        }
        return out;
    }
}
