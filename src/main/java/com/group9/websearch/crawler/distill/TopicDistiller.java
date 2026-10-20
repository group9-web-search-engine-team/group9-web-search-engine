package com.group9.websearch.crawler.distill;

import com.group9.websearch.crawler.persistence.FrontierRepository;
import com.group9.websearch.crawler.persistence.FrontierRepository.LinkEdge;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relevance-conscious topic distillation, adapted from Chakrabarti, van den Berg & Dom
 * (1999), "Focused Crawling", which applies Kleinberg's HITS hub/authority analysis to the
 * crawl's web graph with relevance weighting on the edges.
 *
 * <p>Algorithm (genuine weighted HITS, NOT raw in/out-degree):
 * <ol>
 *   <li>Build the directed graph from the {@code links} table (keyed by URL, so
 *       not-yet-fetched destinations with a null {@code destination_doc_id} are included).</li>
 *   <li>Initialise every node's hub and authority score to 1.</li>
 *   <li>Iterate the mutual-reinforcement updates with per-node relevance weights
 *       {@code w(x)}:
 *       <pre>
 *         auth(p) = sum over edges q-&gt;p of hub(q) * w(q)
 *         hub(p)  = sum over edges p-&gt;r of auth(r) * w(r)
 *       </pre>
 *       and L2-normalise hub and authority vectors after each iteration.</li>
 *   <li>Select the top-N nodes by hub score.</li>
 * </ol>
 *
 * <p><b>Relevance signal.</b> The paper uses a topic-specific relevance classifier to weight
 * nodes. This is a GENERAL-PURPOSE search engine, so we substitute the QT5-Small page-quality
 * score as the relevance weight {@code w(x)}: QT5 scores are log-probabilities (&le; 0,
 * higher = better), mapped to a positive weight {@code exp(quality)} in (0, 1]; pages without a
 * QT5 score use a neutral default weight. This substitution is an explicit engineering
 * adaptation; QT5/QMax are NOT the paper's original relevance algorithm.
 *
 * <p>After selecting top hubs, {@link #distill} promotes the hubs' unvisited, still-eligible
 * (pending) neighbour URLs by raising their {@code distill_boost} in the frontier (via
 * {@link FrontierRepository#promoteHubNeighbour}). The boost improves only the relevance
 * term of the soft-focus ordering; it never lets a higher-attempt URL overtake a
 * lower-attempt one.
 */
public final class TopicDistiller {

    private static final Logger LOG = LoggerFactory.getLogger(TopicDistiller.class);

    /** Neutral relevance weight for nodes without a QT5 quality score. */
    static final double DEFAULT_WEIGHT = 0.5;
    /** HITS iteration count (standard small fixed number; converges quickly). */
    static final int HITS_ITERATIONS = 20;

    private final FrontierRepository repository;
    private final int topHubs;
    private final double boost;

    public TopicDistiller(FrontierRepository repository, int topHubs, double boost) {
        this.repository = repository;
        this.topHubs = Math.max(1, topHubs);
        this.boost = boost;
    }

    /**
     * Run one distillation pass: load the graph, compute hub scores via weighted HITS,
     * select the top hubs, and promote their unvisited pending neighbours. Returns the
     * number of neighbour URLs promoted.
     */
    public int distill() throws SQLException {
        List<LinkEdge> edges = repository.loadLinkEdges();
        if (edges.isEmpty()) {
            LOG.info("Distillation: no link edges yet; nothing to do.");
            return 0;
        }
        Map<String, Double> quality = repository.loadDocumentQuality();
        Map<String, Double> hubScores = computeHubScores(edges, quality);

        List<String> hubs = topHubs(hubScores, topHubs);
        LOG.info("Distillation: selected {} top hub(s) from {} nodes.", hubs.size(), hubScores.size());

        // Collect the hubs' distinct outgoing neighbours, then promote those still pending.
        Map<String, List<String>> outByHub = outgoingByNode(edges);
        Set<String> neighbours = new LinkedHashSet<>();
        for (String hub : hubs) {
            neighbours.addAll(outByHub.getOrDefault(hub, List.of()));
        }
        int promoted = 0;
        for (String n : neighbours) {
            if (repository.promoteHubNeighbour(n, boost)) {
                promoted++;
            }
        }
        LOG.info("Distillation: promoted {} unvisited hub-neighbour URL(s) (+{} boost).", promoted, boost);
        return promoted;
    }

    /**
     * Pure weighted-HITS hub scoring over the edge list with per-node relevance weights
     * derived from QT5 quality. Exposed for unit testing on a small known graph.
     */
    public static Map<String, Double> computeHubScores(List<LinkEdge> edges, Map<String, Double> quality) {
        // Node set and adjacency.
        Map<String, List<String>> out = outgoingByNode(edges);
        Map<String, List<String>> in = new HashMap<>();
        Set<String> nodes = new HashSet<>();
        for (LinkEdge e : edges) {
            nodes.add(e.sourceUrl());
            nodes.add(e.destinationUrl());
            in.computeIfAbsent(e.destinationUrl(), k -> new ArrayList<>()).add(e.sourceUrl());
        }

        Map<String, Double> hub = new HashMap<>();
        Map<String, Double> auth = new HashMap<>();
        for (String n : nodes) {
            hub.put(n, 1.0);
            auth.put(n, 1.0);
        }

        for (int iter = 0; iter < HITS_ITERATIONS; iter++) {
            // authority(p) = sum over q->p of hub(q) * w(q)
            Map<String, Double> newAuth = new HashMap<>();
            for (String p : nodes) {
                double sum = 0.0;
                for (String q : in.getOrDefault(p, List.of())) {
                    sum += hub.getOrDefault(q, 0.0) * weight(q, quality);
                }
                newAuth.put(p, sum);
            }
            // hub(p) = sum over p->r of authority(r) * w(r)
            Map<String, Double> newHub = new HashMap<>();
            for (String p : nodes) {
                double sum = 0.0;
                for (String r : out.getOrDefault(p, List.of())) {
                    sum += newAuth.getOrDefault(r, 0.0) * weight(r, quality);
                }
                newHub.put(p, sum);
            }
            normalise(newAuth);
            normalise(newHub);
            auth = newAuth;
            hub = newHub;
        }
        return hub;
    }

    /** Relevance weight w(x): exp(QT5 log-prob) in (0,1]; neutral default if unscored. */
    static double weight(String url, Map<String, Double> quality) {
        Double q = quality.get(url);
        if (q == null) {
            return DEFAULT_WEIGHT;
        }
        // QT5 score is a log-probability (<= 0). exp maps it to (0, 1]; higher quality ->
        // larger weight. Guard against extreme/invalid values.
        double w = Math.exp(q);
        if (Double.isNaN(w) || Double.isInfinite(w) || w <= 0.0) {
            return DEFAULT_WEIGHT;
        }
        return w;
    }

    static List<String> topHubs(Map<String, Double> hubScores, int n) {
        List<String> ordered = new ArrayList<>(hubScores.keySet());
        // Descending hub score; deterministic tie-break by URL.
        ordered.sort(Comparator.<String>comparingDouble(u -> -hubScores.getOrDefault(u, 0.0))
                .thenComparing(Comparator.naturalOrder()));
        return ordered.subList(0, Math.min(n, ordered.size()));
    }

    private static Map<String, List<String>> outgoingByNode(List<LinkEdge> edges) {
        Map<String, List<String>> out = new HashMap<>();
        for (LinkEdge e : edges) {
            out.computeIfAbsent(e.sourceUrl(), k -> new ArrayList<>()).add(e.destinationUrl());
        }
        return out;
    }

    private static void normalise(Map<String, Double> scores) {
        double norm = 0.0;
        for (double v : scores.values()) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm <= 0.0) {
            return;
        }
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            e.setValue(e.getValue() / norm);
        }
    }
}
