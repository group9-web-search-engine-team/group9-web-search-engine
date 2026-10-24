package com.group9.websearch.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.group9.websearch.index.HitType;
import com.group9.websearch.index.PostingList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PostingOpsTest {

    private static PostingList pl(Object... docFieldPos) {
        // triples: docId(Long), field(HitType), position(Integer)
        PostingList p = new PostingList();
        for (int i = 0; i < docFieldPos.length; i += 3) {
            p.add((Long) docFieldPos[i], (HitType) docFieldPos[i + 1], (Integer) docFieldPos[i + 2]);
        }
        return p;
    }

    @Test
    void unionCollectsAllDocIds() {
        PostingList a = pl(1L, HitType.BODY, 0, 2L, HitType.BODY, 0);
        PostingList b = pl(2L, HitType.BODY, 1, 3L, HitType.BODY, 0);
        assertEquals(Set.of(1L, 2L, 3L), PostingOps.unionDocIds(List.of(a, b)));
    }

    @Test
    void intersectKeepsCommonDocIds() {
        PostingList a = pl(1L, HitType.BODY, 0, 2L, HitType.BODY, 0, 3L, HitType.BODY, 0);
        PostingList b = pl(2L, HitType.BODY, 1, 3L, HitType.BODY, 0, 4L, HitType.BODY, 0);
        assertEquals(Set.of(2L, 3L), PostingOps.intersectDocIds(List.of(a, b)));
    }

    @Test
    void intersectEmptyWhenAnyListMissing() {
        PostingList a = pl(1L, HitType.BODY, 0);
        assertTrue(PostingOps.intersectDocIds(java.util.Arrays.asList(a, null)).isEmpty());
    }

    @Test
    void differenceRemovesExcluded() {
        Set<Long> base = Set.of(1L, 2L, 3L, 4L);
        Set<Long> excluded = Set.of(2L, 4L);
        assertEquals(Set.of(1L, 3L), PostingOps.difference(base, excluded));
    }

    @Test
    void minSpanAdjacentTermsIsNMinus1() {
        // term A at 0,5 ; term B at 1 -> tightest span covering both is |1-0| = 1
        List<List<Integer>> lists = List.of(List.of(0, 5), List.of(1));
        assertEquals(1, PostingOps.minSpan(lists));
    }

    @Test
    void proximityMatchesWithinWindowSameField() {
        // "quick fox": quick@1, fox@2 in body of doc 1 -> span 1
        PostingList quick = pl(1L, HitType.BODY, 1);
        PostingList fox = pl(1L, HitType.BODY, 2);
        Set<Long> near = PostingOps.proximityDocIds(List.of(quick, fox), 2);
        assertEquals(Set.of(1L), near);
    }

    @Test
    void proximityRejectsBeyondWindow() {
        PostingList quick = pl(1L, HitType.BODY, 0);
        PostingList fox = pl(1L, HitType.BODY, 50);
        assertTrue(PostingOps.proximityDocIds(List.of(quick, fox), 5).isEmpty());
    }

    @Test
    void proximityNotAcrossDifferentFields() {
        // quick in title@0, fox in body@0 -> not comparable, no proximity match
        PostingList quick = pl(1L, HitType.TITLE, 0);
        PostingList fox = pl(1L, HitType.BODY, 0);
        assertTrue(PostingOps.proximityDocIds(List.of(quick, fox), 5).isEmpty());
    }
}
