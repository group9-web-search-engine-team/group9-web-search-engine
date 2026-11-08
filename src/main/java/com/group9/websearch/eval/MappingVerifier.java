package com.group9.websearch.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Verifies and persists the CANONICAL BEIR&lt;-&gt;internal document-ID mapping for the WHOLE
 * TREC-COVID corpus, derived losslessly from the full corpus metadata export
 * ({@code documents.jsonl}). This is evaluation-only tooling; it does not touch the index,
 * workers, crawler, or search code.
 *
 * <p><b>Source of truth.</b> Every corpus record in {@code documents.jsonl} carries both the
 * internal numeric {@code docId} and the original BEIR identifier in its {@code url} field as
 * {@code beir:<_id>}. The mapping is therefore an exact extraction -- NO fuzzy PMC/title
 * matching (unlike {@link IdMapBuilder}) and no sampling. Both directions are checked for
 * bijectivity over all records.
 *
 * <p><b>Output.</b> A clearly named, versioned artifact
 * {@code <out>} (default {@code eval/beir-idmap.v1.tsv}) in the canonical
 * {@code beirId<TAB>internalId} form that {@link com.group9.websearch.index.IdMap} /
 * {@link DocIdResolver} already consume. If a correct, complete artifact already exists it is
 * left untouched unless {@code --force} is given.
 *
 * <p><b>Statistics</b> (printed, and written to a {@code <out>.stats.json} sidecar): total
 * corpus records, unique BEIR ids, unique internal ids, successfully mapped, missing BEIR ids,
 * missing internal ids, duplicate BEIR ids, duplicate internal ids, invalid records, and (if a
 * qrels file is supplied) qrels docs that cannot be mapped.
 *
 * <p>Usage:
 * {@code EvalCli verify-idmap --meta documents.jsonl [--out eval/beir-idmap.v1.tsv]
 *  [--qrels qrels.tsv] [--expect-count 171332] [--force]}
 * Exit: 0 all checks pass; 1 a hard failure (not a bijection / count mismatch / duplicates);
 * 2 usage/IO error.
 */
public final class MappingVerifier {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final int SCHEMA_VERSION = 1;

    private MappingVerifier() {
    }

    static int run(String[] args) {
        String meta = "week3-export/documents.jsonl";
        String out = "eval/beir-idmap.v1.tsv";
        String qrels = null;
        int expectCount = -1;
        boolean force = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--meta" -> meta = req(args, ++i, "--meta");
                    case "--out" -> out = req(args, ++i, "--out");
                    case "--qrels" -> qrels = req(args, ++i, "--qrels");
                    case "--expect-count" -> expectCount = Integer.parseInt(req(args, ++i, "--expect-count"));
                    case "--force" -> force = true;
                    default -> {
                        System.err.println("unknown verify-idmap flag: " + args[i]);
                        return 2;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }

        Path metaPath = Path.of(meta);
        if (!Files.isRegularFile(metaPath)) {
            System.err.println("corpus metadata export not found: " + meta);
            return 2;
        }

        // --- Extract the mapping from documents.jsonl (both directions, with dup detection) ---
        Map<String, Long> beirToInternal = new TreeMap<>();
        Map<Long, String> internalToBeir = new HashMap<>();
        long totalRecords = 0;
        long invalidRecords = 0;
        int duplicateBeir = 0;
        int duplicateInternal = 0;
        int missingBeir = 0;      // records with a docId but no beir:<id> url
        int missingInternal = 0;  // records with a beir:<id> url but no docId
        Set<String> allBeir = new HashSet<>();
        Set<Long> allInternal = new HashSet<>();

        try (BufferedReader br = Files.newBufferedReader(metaPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                totalRecords++;
                final JsonNode n;
                try {
                    n = MAPPER.readTree(line);
                } catch (IOException badJson) {
                    invalidRecords++;
                    continue;
                }
                JsonNode idNode = n.get("docId");
                JsonNode urlNode = n.get("url");
                Long internal = (idNode != null && idNode.canConvertToLong()) ? idNode.asLong() : null;
                String beir = null;
                if (urlNode != null && !urlNode.isNull()) {
                    String url = urlNode.asText();
                    if (url.startsWith("beir:")) {
                        beir = url.substring("beir:".length());
                    }
                }
                if (internal == null && beir == null) {
                    invalidRecords++;
                    continue;
                }
                if (internal != null) {
                    allInternal.add(internal);
                }
                if (beir != null) {
                    allBeir.add(beir);
                }
                if (beir == null) {
                    missingBeir++;
                    continue;
                }
                if (internal == null) {
                    missingInternal++;
                    continue;
                }
                if (beirToInternal.containsKey(beir)) {
                    duplicateBeir++;
                    continue; // first-wins; conflict counted
                }
                if (internalToBeir.containsKey(internal)) {
                    duplicateInternal++;
                    continue;
                }
                beirToInternal.put(beir, internal);
                internalToBeir.put(internal, beir);
            }
        } catch (IOException e) {
            System.err.println("could not read corpus metadata: " + e.getMessage());
            return 2;
        }

        int mapped = beirToInternal.size();
        boolean bijection = beirToInternal.size() == internalToBeir.size()
                && duplicateBeir == 0 && duplicateInternal == 0;

        // --- Optional qrels cross-check ---
        int qrelsDistinct = -1;
        int qrelsUnmappable = -1;
        if (qrels != null) {
            try {
                Set<String> qrelsDocs = readQrelsDocs(Path.of(qrels));
                qrelsDistinct = qrelsDocs.size();
                int unmap = 0;
                for (String d : qrelsDocs) {
                    if (!beirToInternal.containsKey(d)) {
                        unmap++;
                    }
                }
                qrelsUnmappable = unmap;
            } catch (IOException e) {
                System.err.println("could not read qrels: " + e.getMessage());
                return 2;
            }
        }

        // --- Report statistics ---
        System.out.println("=== BEIR id-mapping verification (source: " + meta + ") ===");
        System.out.println("total corpus records:     " + totalRecords);
        System.out.println("unique BEIR ids:          " + allBeir.size());
        System.out.println("unique internal ids:      " + allInternal.size());
        System.out.println("successfully mapped:      " + mapped);
        System.out.println("missing BEIR ids:         " + missingBeir);
        System.out.println("missing internal ids:     " + missingInternal);
        System.out.println("duplicate BEIR ids:       " + duplicateBeir);
        System.out.println("duplicate internal ids:   " + duplicateInternal);
        System.out.println("invalid records:          " + invalidRecords);
        System.out.println("one-to-one (bijection):   " + bijection);
        if (expectCount >= 0) {
            System.out.println("expected corpus count:    " + expectCount
                    + (totalRecords == expectCount ? "  (MATCH)" : "  (MISMATCH)"));
        }
        if (qrels != null) {
            System.out.println("qrels distinct docs:      " + qrelsDistinct);
            System.out.println("qrels docs unmappable:    " + qrelsUnmappable);
        }

        boolean hardFail = !bijection
                || (expectCount >= 0 && totalRecords != expectCount);

        // --- Persist the versioned artifact (unless an identical complete one exists) ---
        Path outPath = Path.of(out);
        try {
            boolean exists = Files.isRegularFile(outPath);
            if (exists && !force) {
                System.out.println("\nartifact already exists (not overwriting without --force): "
                        + outPath);
            } else {
                writeArtifact(outPath, beirToInternal);
                System.out.println("\nwrote mapping artifact: " + outPath + "  (v" + SCHEMA_VERSION
                        + ", " + mapped + " entries)");
            }
            writeStats(outPath, totalRecords, allBeir.size(), allInternal.size(), mapped,
                    missingBeir, missingInternal, duplicateBeir, duplicateInternal, invalidRecords,
                    bijection, expectCount, qrelsDistinct, qrelsUnmappable);
        } catch (IOException e) {
            System.err.println("could not write artifact: " + e.getMessage());
            return 2;
        }

        if (hardFail) {
            System.out.println("\nRESULT: FAIL (mapping is not a complete bijection or count "
                    + "mismatch) -- see statistics above.");
            return 1;
        }
        System.out.println("\nRESULT: PASS (complete one-to-one mapping over all "
                + totalRecords + " records).");
        return 0;
    }

    private static void writeArtifact(Path out, Map<String, Long> beirToInternal)
            throws IOException {
        if (out.toAbsolutePath().getParent() != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
        }
        Path tmp = Path.of(out + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, Long> e : beirToInternal.entrySet()) {
                w.write(e.getKey());
                w.write('\t');
                w.write(Long.toString(e.getValue()));
                w.write('\n');
            }
        }
        try {
            Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeStats(Path artifact, long totalRecords, int uniqueBeir,
            int uniqueInternal, int mapped, int missingBeir, int missingInternal,
            int duplicateBeir, int duplicateInternal, long invalidRecords, boolean bijection,
            int expectCount, int qrelsDistinct, int qrelsUnmappable) throws IOException {
        var root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("artifact", artifact.toString());
        root.put("totalCorpusRecords", totalRecords);
        root.put("uniqueBeirIds", uniqueBeir);
        root.put("uniqueInternalIds", uniqueInternal);
        root.put("successfullyMapped", mapped);
        root.put("missingBeirIds", missingBeir);
        root.put("missingInternalIds", missingInternal);
        root.put("duplicateBeirIds", duplicateBeir);
        root.put("duplicateInternalIds", duplicateInternal);
        root.put("invalidRecords", invalidRecords);
        root.put("oneToOne", bijection);
        root.put("expectedCorpusCount", expectCount);
        root.put("qrelsDistinctDocs", qrelsDistinct);
        root.put("qrelsUnmappable", qrelsUnmappable);
        Path statsPath = Path.of(artifact + ".stats.json");
        Files.writeString(statsPath,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        System.out.println("wrote mapping statistics: " + statsPath);
    }

    static Set<String> readQrelsDocs(Path qrelsPath) throws IOException {
        Set<String> docs = new HashSet<>();
        try (BufferedReader br = Files.newBufferedReader(qrelsPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split("\t");
                if (cols.length < 3) {
                    cols = line.trim().split("\\s+");
                }
                if (cols.length < 3) {
                    continue;
                }
                if (lineNo == 1 && !isInt(cols[2].trim())) {
                    continue; // header
                }
                docs.add(cols[1].trim());
            }
        }
        return docs;
    }

    private static boolean isInt(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String req(String[] a, int i, String flag) {
        if (i >= a.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return a[i];
    }
}
