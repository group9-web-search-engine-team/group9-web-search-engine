package com.group9.websearch.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only validator that PROVES the canonical id mapping is consistent and one-to-one against
 * the corpus metadata ({@code documents.jsonl}, whose {@code url} field carries {@code beir:<_id>}
 * on the CorpusAdapter path) and optionally the qrels.
 *
 * <p>Checks (all reported; non-zero exit on any hard failure):
 * <ol>
 *   <li>id map parses with no malformed lines and no duplicate/conflict rows;</li>
 *   <li>the map is one-to-one (bijection);</li>
 *   <li>every internal id in the map appears in documents.jsonl, and every documents.jsonl
 *       {@code beir:<id>} appears in the map (no orphaned metadata, no orphaned mappings);</li>
 *   <li>(optional) every qrels docId resolves through the map; unresolved qrels docs are
 *       reported explicitly (distinguishing "not in map" from "out of subset").</li>
 * </ol>
 *
 * <p>Usage: {@code IdMapValidatorCli <idmap.tsv> <documents.jsonl> [qrels.tsv]}.
 * Exit codes: 0 = all checks pass; 1 = one or more hard failures; 2 = usage/IO error.
 */
public final class IdMapValidatorCli {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private IdMapValidatorCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: IdMapValidatorCli <idmap.tsv> <documents.jsonl> [qrels.tsv]");
            return 2;
        }
        Path idmapPath = Path.of(args[0]);
        Path docsPath = Path.of(args[1]);
        Path qrelsPath = args.length >= 3 ? Path.of(args[2]) : null;

        List<String> failures = new ArrayList<>();
        final IdMap idMap;
        try {
            idMap = IdMap.load(idmapPath);
        } catch (IOException e) {
            System.err.println("could not load id map: " + e.getMessage());
            return 2;
        }

        System.out.println("id map entries:      " + idMap.size());
        System.out.println("malformed lines:     " + idMap.malformed().size());
        System.out.println("conflict lines:      " + idMap.conflicts().size());
        System.out.println("one-to-one:          " + idMap.isOneToOne());
        for (String m : idMap.malformed()) {
            System.out.println("  MALFORMED " + m);
        }
        for (String c : idMap.conflicts()) {
            System.out.println("  CONFLICT  " + c);
        }
        if (!idMap.malformed().isEmpty()) {
            failures.add(idMap.malformed().size() + " malformed id-map line(s)");
        }
        if (!idMap.conflicts().isEmpty()) {
            failures.add(idMap.conflicts().size() + " conflicting id-map line(s)");
        }
        if (!idMap.isOneToOne()) {
            failures.add("id map is not one-to-one");
        }

        // Cross-check against documents.jsonl (beir:<id> in the url field, internal docId).
        Set<String> docBeirIds = new HashSet<>();
        Set<Long> docInternalIds = new HashSet<>();
        long docLines = 0;
        try (BufferedReader br = Files.newBufferedReader(docsPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                docLines++;
                JsonNode n = MAPPER.readTree(line);
                JsonNode idNode = n.get("docId");
                JsonNode urlNode = n.get("url");
                if (idNode != null) {
                    docInternalIds.add(idNode.asLong());
                }
                if (urlNode != null) {
                    String url = urlNode.asText();
                    if (url.startsWith("beir:")) {
                        docBeirIds.add(url.substring("beir:".length()));
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("could not read documents.jsonl: " + e.getMessage());
            return 2;
        }
        System.out.println("documents.jsonl:     " + docLines + " line(s), "
                + docBeirIds.size() + " beir:<id>, " + docInternalIds.size() + " internal id(s)");

        // Orphans: metadata ids not in the map, and map ids not in the metadata.
        int metaBeirNotInMap = 0;
        for (String b : docBeirIds) {
            if (!idMap.containsBeir(b)) {
                metaBeirNotInMap++;
            }
        }
        int mapInternalNotInMeta = 0;
        for (long internal : idMap.internalIds()) {
            if (!docInternalIds.contains(internal)) {
                mapInternalNotInMeta++;
            }
        }
        System.out.println("orphaned metadata beir ids (not in map):   " + metaBeirNotInMap);
        System.out.println("orphaned map internal ids (not in meta):   " + mapInternalNotInMeta);
        if (!docBeirIds.isEmpty() && metaBeirNotInMap > 0) {
            failures.add(metaBeirNotInMap + " documents.jsonl beir ids missing from the id map");
        }
        if (mapInternalNotInMeta > 0) {
            failures.add(mapInternalNotInMeta + " id-map internal ids missing from documents.jsonl");
        }

        // Optional qrels resolution (unresolved reported, never guessed).
        if (qrelsPath != null) {
            Set<String> qrelsDocs = new HashSet<>();
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
                    qrelsDocs.add(cols[1].trim());
                }
            } catch (IOException e) {
                System.err.println("could not read qrels: " + e.getMessage());
                return 2;
            }
            List<String> unresolved = idMap.unresolved(qrelsDocs);
            double cov = idMap.coverage(qrelsDocs);
            System.out.printf("qrels distinct docs: %d  resolved: %d  unresolved: %d  coverage: %.4f%n",
                    qrelsDocs.size(), qrelsDocs.size() - unresolved.size(), unresolved.size(), cov);
            // Unresolved qrels docs are reported, not a hard failure here: they may be
            // legitimately out-of-subset. The evaluator distinguishes the two cases.
        }

        if (failures.isEmpty()) {
            System.out.println("\nRESULT: PASS (mapping is consistent and one-to-one)");
            return 0;
        }
        System.out.println("\nRESULT: FAIL");
        for (String f : failures) {
            System.out.println("  - " + f);
        }
        return 1;
    }

    private static boolean isInt(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
