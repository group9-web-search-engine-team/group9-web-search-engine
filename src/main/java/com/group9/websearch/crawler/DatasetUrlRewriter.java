package com.group9.websearch.crawler;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the stale / non-crawlable URL shapes found in the BEIR TREC-COVID (CORD-19)
 * {@code metadata.url} field into the current canonical HTML page for the same article.
 *
 * <p>Why this exists: the CORD-19 URLs were captured in 2020. Since then several hosts
 * moved, so the raw seed URLs are either denied by robots.txt or only reachable through a
 * redirect to a different host:
 * <ul>
 *   <li>{@code www.ncbi.nlm.nih.gov/pmc/articles/PMCn/} — PMC moved to
 *       {@code pmc.ncbi.nlm.nih.gov} (2024). The old host's robots.txt now has
 *       {@code Disallow: /pmc/articles/} for all agents, which is why the crawler was
 *       "denied by robots.txt" for ~50% of the dataset. The new host explicitly
 *       {@code Allow: /articles/}.</li>
 *   <li>{@code www.ncbi.nlm.nih.gov/pubmed/n} — moved to {@code pubmed.ncbi.nlm.nih.gov/n/}.</li>
 *   <li>{@code europepmc.org/articles/pmcN?pdf=render} — a PDF render of a PMC article;
 *       the same article's HTML lives at {@code pmc.ncbi.nlm.nih.gov/articles/PMCN/}.</li>
 *   <li>{@code arxiv.org/pdf/ID.pdf} — a PDF (rejected by the JSoup parser, and itself a
 *       301). The HTML abstract page {@code arxiv.org/abs/ID} holds the title + abstract,
 *       which is exactly what the TREC-COVID document contains.</li>
 *   <li>{@code medrxiv.org/cgi/content/short/DOI?rss=1} — legacy RSS link (302 chain);
 *       canonical form is {@code www.medrxiv.org/content/10.1101/DOI}.</li>
 * </ul>
 *
 * <p>The rewrite is a pure function: unknown URLs are returned unchanged, so it is safe to
 * apply to seeds, redirect targets and outlinks alike.
 */
public final class DatasetUrlRewriter {

    /** Config key: apply dataset URL rewriting to seeds/outlinks (default true). */
    public static final String ENABLED_PARAM = "crawl.rewrite.dataset.urls";
    public static final boolean DEFAULT_ENABLED = true;

    private record Rule(Pattern pattern, Function<Matcher, String> target) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile(
                    "^https?://(?:www\\.)?ncbi\\.nlm\\.nih\\.gov/pmc/articles/(PMC\\d+)(?:[/?#].*)?$",
                    Pattern.CASE_INSENSITIVE),
                    m -> "https://pmc.ncbi.nlm.nih.gov/articles/" + m.group(1).toUpperCase(Locale.ROOT) + "/"),
            new Rule(Pattern.compile(
                    "^https?://(?:www\\.)?ncbi\\.nlm\\.nih\\.gov/pubmed/(\\d+)(?:[/?#].*)?$",
                    Pattern.CASE_INSENSITIVE),
                    m -> "https://pubmed.ncbi.nlm.nih.gov/" + m.group(1) + "/"),
            new Rule(Pattern.compile(
                    "^https?://(?:www\\.)?europepmc\\.org/articles/(pmc\\d+)(?:[/?#].*)?$",
                    Pattern.CASE_INSENSITIVE),
                    m -> "https://pmc.ncbi.nlm.nih.gov/articles/" + m.group(1).toUpperCase(Locale.ROOT) + "/"),
            new Rule(Pattern.compile(
                    "^https?://(?:www\\.|export\\.)?arxiv\\.org/pdf/([^?#]+?)(?:\\.pdf)?/?(?:[?#].*)?$",
                    Pattern.CASE_INSENSITIVE),
                    m -> "https://arxiv.org/abs/" + m.group(1)),
            new Rule(Pattern.compile(
                    "^https?://(?:www\\.)?medrxiv\\.org/cgi/content/short/([^?#]+?)(?:[?#].*)?$",
                    Pattern.CASE_INSENSITIVE),
                    m -> "https://www.medrxiv.org/content/10.1101/" + m.group(1)));

    private DatasetUrlRewriter() {
    }

    /**
     * Return the canonical crawlable URL for {@code url}, or {@code url} unchanged when no
     * rule applies. {@code null} is returned as {@code null}.
     */
    public static String rewrite(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(trimmed);
            if (m.matches()) {
                return rule.target().apply(m);
            }
        }
        return url;
    }
}
