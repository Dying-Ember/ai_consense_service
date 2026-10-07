package com.consense.service.drafting;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DraftEvidenceQuotesTest {
    @Test void acceptsCurlyAndStraightQuotes() {
        assertTrue(DraftEvidenceQuotes.present("The answer to “39 months?” is No. The tenderer’s period is blank.", "The answer to \"39 months?\" is No."));
        assertTrue(DraftEvidenceQuotes.present("The tenderer’s period is blank.", "The tenderer's period is blank."));
    }
    @Test void rejectsParaphrasesAndJoinedSnippets() {
        assertFalse(DraftEvidenceQuotes.present("The answer is No. Other wording. The ceiling is 31 months.", "The answer is No. The ceiling is 31 months."));
        assertFalse(DraftEvidenceQuotes.present("The ceiling is 31 months.", "The ceiling is 39 months."));
        assertFalse(DraftEvidenceQuotes.present("1 | Preliminaries\n2 | Preambles", "1 | Preliminaries | 2 | Preambles"));
        assertFalse(DraftEvidenceQuotes.present("Any source", ""));
    }
}
