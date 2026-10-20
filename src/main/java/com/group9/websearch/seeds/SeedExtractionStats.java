package com.group9.websearch.seeds;

/**
 * Mutable tally of seed-extraction outcomes. Reported at the end of a run so the
 * operator can see total documents processed, URLs found, invalid URLs skipped and
 * unique URLs written (and the breakdown of why URLs were skipped).
 *
 * <p>Not thread-safe; the seed generator processes the corpus on a single thread.
 */
public final class SeedExtractionStats {

    private long documentsProcessed;
    private long malformedJsonLines;
    private long missingUrl;
    private long blankUrl;
    private long unsupportedOrInvalidUrl;
    private long urlsFound;
    private long duplicateUrls;
    private long uniqueUrlsWritten;

    public void incrementDocumentsProcessed() {
        documentsProcessed++;
    }

    public void incrementMalformedJsonLines() {
        malformedJsonLines++;
    }

    public void incrementMissingUrl() {
        missingUrl++;
    }

    public void incrementBlankUrl() {
        blankUrl++;
    }

    public void incrementUnsupportedOrInvalidUrl() {
        unsupportedOrInvalidUrl++;
    }

    public void incrementUrlsFound() {
        urlsFound++;
    }

    public void incrementDuplicateUrls() {
        duplicateUrls++;
    }

    public void incrementUniqueUrlsWritten() {
        uniqueUrlsWritten++;
    }

    public long documentsProcessed() {
        return documentsProcessed;
    }

    public long malformedJsonLines() {
        return malformedJsonLines;
    }

    public long missingUrl() {
        return missingUrl;
    }

    public long blankUrl() {
        return blankUrl;
    }

    public long unsupportedOrInvalidUrl() {
        return unsupportedOrInvalidUrl;
    }

    public long urlsFound() {
        return urlsFound;
    }

    public long duplicateUrls() {
        return duplicateUrls;
    }

    public long uniqueUrlsWritten() {
        return uniqueUrlsWritten;
    }

    /**
     * Total number of URLs skipped for any reason (missing, blank, unsupported/invalid).
     * Malformed JSON lines are counted separately because no URL field was reachable.
     */
    public long invalidUrlsSkipped() {
        return missingUrl + blankUrl + unsupportedOrInvalidUrl;
    }

    @Override
    public String toString() {
        return "SeedExtractionStats{"
                + "documentsProcessed=" + documentsProcessed
                + ", malformedJsonLines=" + malformedJsonLines
                + ", urlsFound=" + urlsFound
                + ", missingUrl=" + missingUrl
                + ", blankUrl=" + blankUrl
                + ", unsupportedOrInvalidUrl=" + unsupportedOrInvalidUrl
                + ", invalidUrlsSkipped=" + invalidUrlsSkipped()
                + ", duplicateUrls=" + duplicateUrls
                + ", uniqueUrlsWritten=" + uniqueUrlsWritten
                + '}';
    }
}
