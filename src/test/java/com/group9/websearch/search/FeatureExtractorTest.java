package com.group9.websearch.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.index.HitType;
import com.group9.websearch.index.PostingList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeatureExtractorTest {

    @Test
    void countsFieldSpecificMatches() {
        // doc 1: "cat" in title@0 and body@0,2 ; "dog" in body@1
        PostingList cat = new PostingList();
        cat.add(1L, HitType.TITLE, 0);
        cat.add(1L, HitType.BODY, 0);
        cat.add(1L, HitType.BODY, 2);
        PostingList dog = new PostingList();
        dog.add(1L, HitType.BODY, 1);

        Map<String, PostingList> tp = new LinkedHashMap<>();
        tp.put("cat", cat);
        tp.put("dog", dog);

        FeatureVector fv = new FeatureExtractor().extract(1L, List.of("cat", "dog"), tp);
        assertEquals(1.0, fv.get(Feature.TITLE_MATCHES), 1e-9);   // only cat in title
        assertEquals(2.0, fv.get(Feature.BODY_MATCHES), 1e-9);    // cat and dog in body
        assertEquals(0.0, fv.get(Feature.ANCHOR_MATCHES), 1e-9);  // anchor not indexed
        assertEquals(1.0, fv.get(Feature.QUERY_COVERAGE), 1e-9);  // both terms present
        assertEquals(0.5, fv.get(Feature.TITLE_COVERAGE), 1e-9);  // 1 of 2 terms in title
        assertTrue(fv.get(Feature.BODY_TF) > 0.0);
    }

    @Test
    void proximityHigherWhenTermsAdjacent() {
        // adjacent: cat@0, dog@1  vs  far: cat@0, dog@20
        PostingList catNear = new PostingList();
        catNear.add(1L, HitType.BODY, 0);
        PostingList dogNear = new PostingList();
        dogNear.add(1L, HitType.BODY, 1);

        PostingList catFar = new PostingList();
        catFar.add(2L, HitType.BODY, 0);
        PostingList dogFar = new PostingList();
        dogFar.add(2L, HitType.BODY, 20);

        FeatureExtractor fx = new FeatureExtractor();
        Map<String, PostingList> near = new LinkedHashMap<>();
        near.put("cat", catNear);
        near.put("dog", dogNear);
        Map<String, PostingList> far = new LinkedHashMap<>();
        far.put("cat", catFar);
        far.put("dog", dogFar);

        double pNear = fx.extract(1L, List.of("cat", "dog"), near).get(Feature.PROXIMITY);
        double pFar = fx.extract(2L, List.of("cat", "dog"), far).get(Feature.PROXIMITY);
        assertTrue(pNear > pFar, "closer terms should score higher proximity");
    }

    @Test
    void pageRankUsedOnlyWhenSupplied() {
        PostingList cat = new PostingList();
        cat.add(1L, HitType.BODY, 0);
        Map<String, PostingList> tp = Map.of("cat", cat);

        // No source -> 0
        double none = new FeatureExtractor().extract(1L, List.of("cat"), tp).get(Feature.PAGERANK);
        assertEquals(0.0, none, 1e-9);

        // Supplied source -> used verbatim
        FeatureExtractor withPr = new FeatureExtractor(docId -> docId == 1L ? 0.42 : null);
        double pr = withPr.extract(1L, List.of("cat"), tp).get(Feature.PAGERANK);
        assertEquals(0.42, pr, 1e-9);
    }
}
