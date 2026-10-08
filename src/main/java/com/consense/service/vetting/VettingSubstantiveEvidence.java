package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import com.consense.web.dto.VettingDtos.FindingEvidence;
import java.util.*;
import java.util.regex.Pattern;

/** Bare section identifiers locate a label; they do not establish the target's content. */
final class VettingSubstantiveEvidence {
    private static final String LABEL = "[\\p{L}\\p{N}]+(?:[./-][\\p{L}\\p{N}]+)*";
    private static final Pattern STANDALONE_LABEL = Pattern.compile(
            "(?:APPENDIX|ANNEX|SCHEDULE)\\s+" + LABEL
                    + "(?:\\s+TO\\s+(?:APPENDIX|ANNEX|SCHEDULE)\\s+" + LABEL + ")?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    static boolean standaloneLabel(String text) {
        return text != null && STANDALONE_LABEL.matcher(text.replace('\u00a0', ' ').trim()).matches();
    }
    static boolean substantive(Chunk chunk, Quote quote) {
        return chunk != null && quote != null && !standaloneLabel(chunk.getContent()) && !standaloneLabel(quote.getQuote());
    }
    static long distinct(List<Quote> quotes, List<FindingEvidence> located, Map<String,Chunk> byId) {
        Set<String> anchors = new HashSet<>();
        for (int i = 0; i < located.size(); i++) {
            Quote quote = quotes.get(i); FindingEvidence evidence = located.get(i);
            if (evidence.isLocated() && substantive(byId.get(quote.getChunkId()), quote))
                anchors.add(evidence.getDocumentId() + "|" + evidence.getAnchor());
        }
        return anchors.size();
    }
}
