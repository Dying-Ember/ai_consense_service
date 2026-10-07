package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParseProbe;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class VettingCorpusConstructionProbeTest {
    @TempDir Path temp;
    SourceDocument source(){
        DocumentBlock block=new DocumentBlock();block.setId("body:0:paragraph");block.setLocation("body/0/paragraph");block.setKind("paragraph");
        block.setText(String.join(" ",Collections.nCopies(500,"Original explicit condition remains in its source scope.")));block.setOriginalText(block.getText());
        SourceDocument d=new SourceDocument();d.setId(21L);d.setProjectId("p");d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileName("source.docx");d.setFileKey("OTHER");d.setReviewRole("tender");
        d.setTextContent(block.getText());d.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));d.setParseStatus("PARSED");return d;
    }
    @Test void longBlockSplitUnionAndReturnedChunksAreExactlyOriginalWhileHistoricParseTimeStaysUnknown()throws Exception{
        SourceDocument d=source();ConsenseProperties props=new ConsenseProperties();props.getDocument().setProbeDirectory(temp.toString());
        List<Chunk> expected=VettingCorpus.chunks(Collections.singletonList(d));
        List<Chunk> observed=VettingCorpusConstructionProbe.chunks(props,"run","p",Collections.singletonList(d));
        assertEquals(JsonUtils.write(expected),JsonUtils.write(observed));assertTrue(observed.size()>1);
        List<JsonNode> events;
        try(Stream<Path> files=Files.list(temp.resolve("corpus-run"))){events=files.filter(p->p.getFileName().toString().matches("[0-9]{5}-.*\\.json"))
            .map(p->{try{return JsonUtils.parse(new String(Files.readAllBytes(p),StandardCharsets.UTF_8));}catch(Exception e){throw new RuntimeException(e);}}).collect(Collectors.toList());}
        JsonNode audit=events.stream().filter(e->"stored_source_chunks".equals(e.path("phase").asText())).findFirst().get().path("observations");
        assertEquals(0,audit.path("uncoveredUtf16Chars").asInt());assertEquals(d.getTextContent().length(),audit.path("coveredUtf16Chars").asInt());
        assertEquals(VettingCorpus.sourceHash(d),audit.path("sourceHash").asText());assertTrue(audit.path("historicalParseOcrTime").isNull());
        assertTrue(audit.path("partChecks").size()>1);assertEquals("unknown",audit.path("qualifiersComplete").asText());
    }
    @Test void disabledProbeProducesNoFilesAndOriginalChunksAndDuplicateSourceIdentityIsFailClosed()throws Exception{
        SourceDocument d=source();ConsenseProperties props=new ConsenseProperties();
        assertEquals(JsonUtils.write(VettingCorpus.chunks(Collections.singletonList(d))),JsonUtils.write(VettingCorpusConstructionProbe.chunks(props,"run","p",Collections.singletonList(d))));
        try(Stream<Path> files=Files.list(temp)){assertEquals(0,files.count());}
        props.getDocument().setProbeDirectory(temp.toString());
        assertThrows(IllegalStateException.class,()->VettingCorpusConstructionProbe.chunks(props,"duplicate","p",Arrays.asList(d,d)));
        assertTrue(Files.exists(temp.resolve("corpus-duplicate/probe_overhead_manifest.json")));
    }
}
