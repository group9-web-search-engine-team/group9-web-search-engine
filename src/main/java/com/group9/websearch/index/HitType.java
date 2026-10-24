package com.group9.websearch.index;

/**
 * The field / occurrence (hit) type a term position belongs to.
 *
 * <p>These are the hit types described in the project requirements. A posting keeps the
 * positions of a term in each field <em>separately</em> so that field-aware ranking (e.g.
 * weighting title/anchor hits above body hits) and positional/proximity retrieval remain
 * possible. Positions in different fields are never merged into one indistinguishable list.
 *
 * <p>Availability from the current indexer input ({@link ExportedDocument}, produced by
 * {@link DocumentExporter}): {@link #TITLE}, {@link #URL} and {@link #BODY} are populated.
 * {@link #ANCHOR} (incoming-link anchor text) and {@link #METADATA} are modelled here as
 * first-class hit types with full propagation support, but the exporter does not currently
 * supply their text to the indexer, so no positions are produced for them yet. That is a
 * documented input limitation, not a representational one -- when the exporter is extended to
 * emit anchor/metadata text, indexing them requires no change to this model.
 *
 * <p>Font prominence and capitalization are additional per-hit signals. They are only emitted
 * where the source data supports them; the current plain-text {@link ExportedDocument} input
 * does not carry font/markup information, so those signals are not produced yet (same
 * extension-point rationale as anchor/metadata).
 *
 * <p>The {@link #code} is the stable, single-character on-disk tag used in the persisted index
 * format. It must never change for an existing value without a format version bump.
 */
public enum HitType {
    TITLE('t'),
    URL('u'),
    ANCHOR('a'),
    METADATA('m'),
    BODY('b');

    private final char code;

    HitType(char code) {
        this.code = code;
    }

    /** Stable single-character tag for the persisted format. */
    public char code() {
        return code;
    }

    /** Resolve a {@link HitType} from its {@link #code}; throws on an unknown tag. */
    public static HitType fromCode(char code) {
        for (HitType h : values()) {
            if (h.code == code) {
                return h;
            }
        }
        throw new IllegalArgumentException("unknown hit-type code: '" + code + "'");
    }
}
