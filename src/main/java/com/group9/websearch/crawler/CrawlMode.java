package com.group9.websearch.crawler;

/**
 * Explicit crawl startup mode.
 *
 * <ul>
 *   <li>{@link #RESUME} (DEFAULT): preserve existing documents / links / crawl progress,
 *       safely recover stale {@code in_progress} frontier rows back to {@code pending},
 *       and continue eligible pending work. The database is NEVER reset in this mode.</li>
 *   <li>{@link #CLEAN_START}: reset the crawl tables (links, documents, crawl_state) in a
 *       single FK-respecting transaction, then re-initialize the frontier from the
 *       configured seed dataset. S3 raw-HTML objects are preserved by default.</li>
 * </ul>
 *
 * <p>The mode is chosen explicitly on the command line ({@code --mode clean-start} or
 * {@code --mode resume}); there is no implicit reset on startup.
 */
public enum CrawlMode {
    RESUME,
    CLEAN_START;

    /** Default mode when none is specified: never reset automatically. */
    public static final CrawlMode DEFAULT = RESUME;

    /**
     * Parse a mode token ({@code resume} or {@code clean-start}, case-insensitive,
     * hyphen or underscore). Returns {@link #DEFAULT} for null/blank.
     */
    public static CrawlMode parse(String token) {
        if (token == null || token.isBlank()) {
            return DEFAULT;
        }
        String t = token.trim().toLowerCase().replace('_', '-');
        switch (t) {
            case "resume":
                return RESUME;
            case "clean-start":
            case "cleanstart":
                return CLEAN_START;
            default:
                throw new IllegalArgumentException(
                        "Unknown crawl mode '" + token + "'. Use 'resume' or 'clean-start'.");
        }
    }
}
