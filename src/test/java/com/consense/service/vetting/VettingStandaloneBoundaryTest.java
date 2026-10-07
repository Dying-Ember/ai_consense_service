package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VettingStandaloneBoundaryTest {
    @Test void qualifiedStandaloneLabelsEndThePreviousScopeAndNeverInventTheNextOwner() {
        for(String label:Arrays.asList("APPENDIX QRS.X7/IV","Annex A-2","schedule QRS.7/2-A","APPENDIX QRS.K4/Ⅲ")) {
            DocumentBlock marker=p("marker",label,null),preface=p("preface","Independent section preface.",null);
            List<Chunk> chunks=chunks(p("old","QRS6 Existing contract provision",null),p("tail","Old source obligation.",null),marker,preface,p("next","QRS7 Next contract provision",null));
            assertEquals(3,chunks.size());assertEquals("QRS6",chunks.get(0).getClauseId());
            assertNull(chunks.get(1).getClauseId());assertNull(chunks.get(1).getClauseHeadingLocation());
            assertEquals(label+"\n"+preface.getText(),chunks.get(1).getContent());
            assertEquals(Arrays.asList("marker","preface"),ids(chunks.get(1)));
            assertEquals("QRS7",chunks.get(2).getClauseId());assertEquals("next",chunks.get(2).getClauseHeadingLocation());
            assertFalse(chunks.get(0).getContent().contains(label));assertFalse(chunks.get(2).getContent().contains(label));
        }
    }
    @Test void realTitleAndSubtitleStopInheritedOwnershipThroughAnUnnumberedPreface() {
        for(String style:Arrays.asList("Title","Subtitle","副标题","副標題")) {
            DocumentBlock marker=p("title","Independent section title",style);
            DocumentBlock preface=p("preface","Original introduction to the following chapter.",null);
            List<Chunk> chunks=chunks(p("old","QRS3 Original clause",null),marker,preface,p("next","QRS4 Next clause",null));
            assertEquals(3,chunks.size());assertEquals("QRS3",chunks.get(0).getClauseId());
            assertNull(chunks.get(1).getClauseId());assertNull(chunks.get(1).getClauseHeadingLocation());
            assertEquals(Arrays.asList("title","preface"),ids(chunks.get(1)));
            assertEquals("QRS4",chunks.get(2).getClauseId());
        }
    }
    @Test void bareDatesAndNumbersInTitleStylesCannotDeclareContractClauses() {
        for(String title:Arrays.asList("2026 September document edition","12 Summary","4.2 Contents heading")) {
            for(String style:Arrays.asList("Title","Subtitle")) {
                List<Chunk> chunks=chunks(p("old","QRS1 Contract scope",null),p("title",title,style),p("body","Unowned section content.",null));
                assertEquals(2,chunks.size());assertNull(chunks.get(1).getClauseId());assertNull(chunks.get(1).getClauseHeadingLocation());
            }
        }
    }
    @Test void explicitOwnedCanonicalHeadingStillDeclaresItsActualLocationInATitleStyle() {
        List<Chunk> chunks=chunks(p("old","QRS1 Earlier clause",null),p("canonical","QRS8 Defined submission section","Title"));
        assertEquals(2,chunks.size());assertEquals("QRS8",chunks.get(1).getClauseId());assertEquals("canonical",chunks.get(1).getClauseHeadingLocation());
    }
    @Test void inlineReferencesAndOrdinaryProseAreNotStandaloneSectionBoundaries() {
        List<String> prose=Arrays.asList("See APPENDIX QRS.X7/IV for details.","This ANNEX A-2 contains additional conditions.",
            "The contractor shall follow SCHEDULE QRS.7/2-A.","APPENDIX QRS.X7/IV is referred to in this provision.",
            "Documents from Annex 1 to APPENDIX QRS.X7/II are needed.",
            "APPENDIX IV TO BE SUBMITTED WITH THE TENDER", "ANNEX II TO BE PROVIDED BY THE CONTRACTOR");
        List<DocumentBlock> blocks=new ArrayList<>();blocks.add(p("heading","QRS6 Original source requirements",null));
        for(int i=0;i<prose.size();i++)blocks.add(p("body"+i,prose.get(i),null));
        List<Chunk> chunks=chunks(blocks.toArray(new DocumentBlock[0]));assertEquals(1,chunks.size());
        assertEquals("QRS6",chunks.get(0).getClauseId());assertEquals(prose.size()+1,chunks.get(0).getParts().size());
    }
    @Test void exactAppendixValuesAndInlineCellReferencesNeverResetTheOwningClause() {
        DocumentBlock ordinary=table("row","Item","See APPENDIX QRS.X7/IV for details.");
        DocumentBlock title=table("table-title","ANNEX QRS.B3/II-A","");
        DocumentBlock required=table("required","APPENDIX QRS.B3/IV","Required attachment");
        DocumentBlock firstLine=table("first-line","SCHEDULE QRS.B3/IV-A\nRequired attachment value","");
        List<Chunk> chunks=chunks(p("heading","QRS6 Source clause",null),ordinary,title,required,firstLine,table("body","Original payload","") );
        assertEquals(1,chunks.size());assertEquals("QRS6",chunks.get(0).getClauseId());assertEquals("heading",chunks.get(0).getClauseHeadingLocation());
        assertEquals(Arrays.asList("heading","row","table-title","required","first-line","body"),ids(chunks.get(0)));
    }
    @Test void contentsEntriesAndNumericStylesWithoutResolvedTitleSemanticsDoNotResetOwnership() {
        DocumentBlock toc=p("toc","APPENDIX QRS.X7/IV","toc 3");
        DocumentBlock unknown=p("numeric-style","2026 Document edition",null);unknown.setParagraphStyle("84");
        List<Chunk> chunks=chunks(p("heading","QRS6 Original source requirements",null),toc,unknown);
        assertEquals(1,chunks.size());assertEquals("QRS6",chunks.get(0).getClauseId());assertEquals(Arrays.asList("heading","toc","numeric-style"),ids(chunks.get(0)));
    }
    @Test void foreignOrUnresolvableStandaloneTitlesLeaveUnknownRatherThanThePreviousOwner() {
        for(String label:Arrays.asList("APPENDIX FGN.K7/IV","Annex 1 to APPENDIX FGN.K7/II")) {
            List<Chunk> chunks=chunks(p("old","QRS6 Existing source scope",null),p("title",label,null),p("next","FGN7 A foreign identifier",null));
            assertEquals(2,chunks.size());assertNull(chunks.get(1).getClauseId());assertNull(chunks.get(1).getClauseHeadingLocation());
        }
    }
    @Test void allSourceTextPartsOffsetsAndRevisionRemainExactAcrossBoundaryAndLongSplits() {
        DocumentBlock old=p("old","QRS6 Existing scope",null),marker=p("marker","APPENDIX QRS.K7/Ⅲ","Subtitle");
        DocumentBlock longBody=p("long","New section original 🏠 text. ".repeat(180)+"FINAL-ORIGINAL-TAIL",null);
        marker.setDeletedText("Deleted option retained in parser only");marker.setStrikeText("Struck option retained in parser only");
        SourceDocument source=doc(old,marker,longBody);String structure=source.getStructuredContentJson(),text=source.getTextContent(),hash=VettingCorpus.sourceHash(source);
        List<Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));Map<String,DocumentBlock> originals=new LinkedHashMap<>();for(DocumentBlock b:Arrays.asList(old,marker,longBody))originals.put(b.getId(),b);
        Set<String> retained=new LinkedHashSet<>();for(Chunk c:chunks) {
            assertEquals(hash,c.getSourceHash());assertNull(c.getPageNo());assertEquals(VettingCorpus.SEGMENTATION_VERSION,c.getSegmentationVersion());
            for(Part part:c.getParts()) {retained.add(part.getBlockId());DocumentBlock b=originals.get(part.getBlockId());
                assertEquals(b.getText().substring(part.getStartOffset(),part.getEndOffset()),part.getText());
                if(!part.getBlockId().equals("old")){assertNull(c.getClauseId());assertNull(c.getClauseHeadingLocation());assertFalse(part.getAnchor().startsWith("QRS6"));}
            }
        }
        assertEquals(originals.keySet(),retained);assertTrue(chunks.stream().anyMatch(c->c.getContent().contains("FINAL-ORIGINAL-TAIL")));
        assertFalse(chunks.stream().anyMatch(c->c.getContent().contains(marker.getDeletedText())||c.getContent().contains(marker.getStrikeText())));
        assertEquals(structure,source.getStructuredContentJson());assertEquals(text,source.getTextContent());assertEquals(hash,VettingCorpus.sourceHash(source));
    }
    @Test void newSegmentationIdentityIsDeterministicAndLegacyChunksRemainReadable() {
        DocumentBlock b=p("one","QRS6 A source requirement",null);SourceDocument d=doc(b);Chunk c=VettingCorpus.chunks(Collections.singletonList(d)).get(0);
        String legacySalt=VettingCorpus.METADATA_VERSION+"|"+d.getId()+"|"+VettingCorpus.sourceHash(d)+"|"+b.getId();
        assertNotEquals(UUID.nameUUIDFromBytes(legacySalt.getBytes(StandardCharsets.UTF_8)).toString(),c.getId());
        assertEquals(c.getId(),VettingCorpus.chunks(Collections.singletonList(d)).get(0).getId());assertEquals("owner-clause-v2",c.getMetadataVersion());
        Chunk legacy=JsonUtils.read("{\"id\":\"legacy-id\",\"content\":\"Original stored content\",\"metadataVersion\":\"owner-clause-v2\"}",Chunk.class);
        assertNull(legacy.getSegmentationVersion());assertEquals("legacy-id",legacy.getId());
    }
    @Test void repeatedCanonicalClauseAfterANewSectionUsesItsNewActualHeadingLocation() {
        List<Chunk> chunks=chunks(p("old","QRS6 Earlier requirements",null),p("title","SCHEDULE QRS.B2/I",null),p("preface","Independent preface",null),p("new","QRS6 Different source section",null));
        assertEquals(3,chunks.size());assertEquals("old",chunks.get(0).getClauseHeadingLocation());assertNull(chunks.get(1).getClauseHeadingLocation());assertEquals("new",chunks.get(2).getClauseHeadingLocation());
    }
    private static List<String> ids(Chunk c){return c.getParts().stream().map(Part::getBlockId).collect(Collectors.toList());}
    private static List<Chunk> chunks(DocumentBlock... blocks){return VettingCorpus.chunks(Collections.singletonList(doc(blocks)));}
    private static SourceDocument doc(DocumentBlock... blocks){SourceDocument d=new SourceDocument();d.setId(701L);d.setFileKey("QRS");d.setFileName("QRS-original.docx");d.setCategory("VETTING_PACKAGE");d.setStructuredContentJson(JsonUtils.write(Arrays.asList(blocks)));d.setTextContent(Arrays.stream(blocks).map(DocumentBlock::getText).collect(Collectors.joining("\n")));return d;}
    private static DocumentBlock p(String id,String text,String style){DocumentBlock b=new DocumentBlock();b.setId(id);b.setLocation(id);b.setKind("paragraph");b.setText(text);b.setParagraphStyle(style==null?null:"native-style");b.setParagraphStyleName(style);return b;}
    private static DocumentBlock table(String id,String... cells){DocumentBlock b=p(id,String.join(" | ",cells),null);b.setKind("table_row");b.setCells(Arrays.asList(cells));return b;}
}
