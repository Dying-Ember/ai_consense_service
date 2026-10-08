package com.consense.service.drafting;

/** Accept typographic quote variants without accepting paraphrases or joined snippets. */
public final class DraftEvidenceQuotes {
    private DraftEvidenceQuotes() { }
    private static String canonical(String value) {
        return value.replace('\u201c', '"').replace('\u201d', '"')
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u00a0', ' ').trim().replaceAll("\\s+", " ");
    }
    /** Complete text equality key for same-source support; actual values and quotations stay unchanged. */
    static String textIdentity(String value) {
        return value == null ? null : canonical(value);
    }
    public static boolean present(String source, String quote) {
        return source != null && quote != null && !quote.trim().isEmpty()
                && canonical(source).contains(canonical(quote));
    }
}
