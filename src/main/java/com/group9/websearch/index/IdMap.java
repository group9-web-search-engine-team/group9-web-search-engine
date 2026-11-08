package com.group9.websearch.index;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The ONE canonical document-identity model for the project.
 *
 * <p>Maps the external BEIR {@code _id} (opaque string, preserved EXACTLY) to the internal
 * numeric docId assigned by indexing, and back. This is the single source of truth consumed by
 * corpus metadata resolution, postings resolution, query-result translation, and qrels
 * resolution, so every stage uses the same mapping (no per-stage ad-hoc guessing).
 *
 * <p>Canonical on-disk format (exactly what {@link CorpusAdapter} writes): a TSV with
 * {@code <beirId>\t<internalId>} per line, {@code internalId} a non-negative long. The loader is
 * STRICT and never fabricates identity:
 * <ul>
 *   <li><b>Malformed</b> lines (wrong column count, non-numeric id) are collected and reported.</li>
 *   <li><b>Duplicate</b> keys -- a BEIR id mapped twice, or an internal id reused for two BEIR
 *       ids -- are collected as conflicts; the FIRST wins deterministically and the conflict is
 *       reported. A conflicting map is NOT silently merged.</li>
 *   <li>The map is validated {@link #isOneToOne() one-to-one}; callers that require a bijection
 *       (indexing/evaluation) can assert it.</li>
 * </ul>
 * No title/URL/PMC heuristic is performed here: identity comes only from this explicit map, so
 * a document is never silently identified by approximate matching. Unresolvable ids are reported
 * as unresolved, never guessed.
 */
public final class IdMap {

    private final Map<String, Long> beirToInternal;
    private final Map<Long, String> internalToBeir;
    private final List<String> malformed;
    private final List<String> conflicts;

    private IdMap(Map<String, Long> beirToInternal, Map<Long, String> internalToBeir,
            List<String> malformed, List<String> conflicts) {
        this.beirToInternal = beirToInternal;
        this.internalToBeir = internalToBeir;
        this.malformed = malformed;
        this.conflicts = conflicts;
    }

    public Optional<Long> toInternal(String beirId) {
        return Optional.ofNullable(beirToInternal.get(beirId));
    }

    public Optional<String> toBeir(long internalId) {
        return Optional.ofNullable(internalToBeir.get(internalId));
    }

    public int size() {
        return beirToInternal.size();
    }

    public boolean containsBeir(String beirId) {
        return beirToInternal.containsKey(beirId);
    }

    public Set<String> beirIds() {
        return Collections.unmodifiableSet(beirToInternal.keySet());
    }

    public Set<Long> internalIds() {
        return Collections.unmodifiableSet(internalToBeir.keySet());
    }

    /** Lines that could not be parsed (reported, never guessed). */
    public List<String> malformed() {
        return Collections.unmodifiableList(malformed);
    }

    /** Duplicate/conflicting mapping lines (first-wins; conflict reported). */
    public List<String> conflicts() {
        return Collections.unmodifiableList(conflicts);
    }

    /** True iff the mapping is a strict bijection (|beir->internal| == |internal->beir|). */
    public boolean isOneToOne() {
        return beirToInternal.size() == internalToBeir.size() && conflicts.isEmpty();
    }

    /**
     * Coverage of a set of external ids: fraction present in the map. Returns 0.0 for an empty
     * input set. Used to report qrels/benchmark coverage honestly.
     */
    public double coverage(Set<String> externalIds) {
        if (externalIds.isEmpty()) {
            return 0.0;
        }
        int mapped = 0;
        for (String id : externalIds) {
            if (beirToInternal.containsKey(id)) {
                mapped++;
            }
        }
        return mapped / (double) externalIds.size();
    }

    /** External ids NOT present in this map (the unresolved set). */
    public List<String> unresolved(Set<String> externalIds) {
        List<String> out = new ArrayList<>();
        for (String id : externalIds) {
            if (!beirToInternal.containsKey(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** Load and validate the canonical {@code beirId<TAB>internalId} TSV. */
    public static IdMap load(Path tsv) throws IOException {
        if (!Files.isRegularFile(tsv)) {
            throw new IOException("id map file not found: " + tsv);
        }
        Map<String, Long> b2i = new HashMap<>();
        Map<Long, String> i2b = new HashMap<>();
        List<String> malformed = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(tsv, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    malformed.add("idmap:" + lineNo + " expected 'beirId<TAB>internalId'");
                    continue;
                }
                String beir = line.substring(0, tab).trim();
                String idStr = line.substring(tab + 1).trim();
                final long internal;
                try {
                    internal = Long.parseLong(idStr);
                } catch (NumberFormatException e) {
                    malformed.add("idmap:" + lineNo + " non-numeric internal id '" + idStr + "'");
                    continue;
                }
                if (internal < 0) {
                    malformed.add("idmap:" + lineNo + " negative internal id " + internal);
                    continue;
                }
                // First-wins with explicit conflict reporting (never silently overwrite).
                if (b2i.containsKey(beir)) {
                    conflicts.add("idmap:" + lineNo + " duplicate beirId '" + beir
                            + "' (keeping first -> " + b2i.get(beir) + ")");
                    continue;
                }
                if (i2b.containsKey(internal)) {
                    conflicts.add("idmap:" + lineNo + " internal id " + internal
                            + " reused for beirId '" + beir + "' (already '"
                            + i2b.get(internal) + "')");
                    continue;
                }
                b2i.put(beir, internal);
                i2b.put(internal, beir);
            }
        }
        return new IdMap(b2i, i2b, malformed, conflicts);
    }

    /** An empty map (everything unresolved). */
    public static IdMap empty() {
        return new IdMap(new HashMap<>(), new HashMap<>(), new ArrayList<>(), new ArrayList<>());
    }
}
