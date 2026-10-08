package com.consense.service.vetting;
import com.consense.common.JsonUtils;import com.consense.document.DocumentBlock;import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.Chunk;import com.fasterxml.jackson.databind.JsonNode;import java.util.*;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class VettingSourceQualityTest {
 @Test void oldOcrDeclarationSurvivesCorpusWithoutBeingCalledVerifiedOrComplete() {
  Chunk c=VettingCorpus.chunks(Collections.singletonList(source(1L,"ocr"))).get(0);JsonNode payload=JsonUtils.parse(JsonUtils.write(c));
  assertEquals("needs_review",payload.path("sourceQuality").path("ocrQualityStatus").asText());
  assertTrue(payload.path("sourceQuality").path("qualityPageScopeUnknown").asBoolean());
  assertEquals("ocr",payload.path("parts").get(0).path("extractionSource").asText());
 }
 @Test void selectedSourceMaterialIncludesBoundParserQualityAndMethods() {
  Chunk c=VettingCorpus.chunks(Collections.singletonList(source(2L,"ocr"))).get(0);JsonNode e=JsonUtils.parse(JsonUtils.write(VettingSourceMaterial.project(Collections.singletonList(c)))).get(0);
  assertEquals("needs_review",e.path("sourceQuality").path("ocrQualityStatus").asText());
  assertEquals("unverified",e.path("sourceQuality").path("textAccuracy").asText());
  assertTrue(e.path("sourceQualityHash").asText().matches("[a-f0-9]{64}"));
 }
 @Test void freshOcrReviewPagesAreDeclarationsAndDoNotCertifyTheText() {
  SourceDocument d=source(3L,"ocr");d.setParseStatus("PARTIAL");d.setPageCount(3);
  d.setParseCoverageJson("{\"totalPages\":3,\"parsedPages\":3,\"ocrPages\":2,\"complete\":true,\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":[3,1,1],\"ocrQualityPageScopeUnknown\":false}");
  VettingSourceQuality.Info q=VettingSourceQuality.from(d,VettingCorpus.blocks(d));
  assertEquals(Arrays.asList(1,3),q.getNeedsReviewPages());assertFalse(q.isQualityPageScopeUnknown());assertTrue(q.getExtractionCoverageComplete());
  assertEquals("PARTIAL",q.getParseStatus());assertEquals("needs_review",q.getOcrQualityStatus());assertEquals("unverified",q.getTextAccuracy());
 }
 @Test void malformedDuplicateWrappedTrailingAndInvalidPageCoverageStaysUnknown() {
  for(String json:Arrays.asList("{bad", "[]", "{\"ocrPages\":1,\"ocrPages\":0}", "{\"ocrPages\":1} {}", "null", "{\"totalPages\":1,\"ocrPages\":1,\"complete\":true,\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":[0,2,\"1\"],\"ocrQualityPageScopeUnknown\":false}")) {
   SourceDocument d=source(4L,"ocr");d.setParseCoverageJson(json);VettingSourceQuality.Info q=VettingSourceQuality.from(d,VettingCorpus.blocks(d));
   assertEquals("needs_review",q.getOcrQualityStatus(),json);assertTrue(q.isQualityPageScopeUnknown(),json);assertTrue(q.getNeedsReviewPages().isEmpty(),json);
  }
 }
 @Test void partialKnownPageListNeverExpandsToUnobservedPagesOrTreatsOcrFlagAsAllPages() {
  SourceDocument d=source(5L,"ocr");d.setPageCount(10);d.setParseCoverageJson("{\"totalPages\":10,\"parsedPages\":10,\"ocrPages\":8,\"complete\":true,\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":[2],\"ocrQualityPageScopeUnknown\":false}");
  VettingSourceQuality.Info q=VettingSourceQuality.from(d,VettingCorpus.blocks(d));assertEquals(Collections.singletonList(2),q.getNeedsReviewPages());assertTrue(q.isQualityPageScopeUnknown());
  d.setParseCoverageJson(null);q=VettingSourceQuality.from(d,VettingCorpus.blocks(d));assertTrue(q.getNeedsReviewPages().isEmpty());assertTrue(q.isQualityPageScopeUnknown());
 }
 @Test void nativeAndPhysicallyBlankDeclarationsNeverBecomeTextQualityVerified() {
  SourceDocument d=source(6L,"native");d.setOcrUsed(false);d.setParseCoverageJson("{\"totalPages\":1,\"parsedPages\":1,\"ocrPages\":0,\"blankPages\":[1],\"complete\":true}");
  d.setTextContent("");d.setStructuredContentJson("[]");VettingSourceQuality.Info q=VettingSourceQuality.from(d,Collections.emptyList());
  assertEquals("not_observed",q.getOcrQualityStatus());assertEquals("unverified",q.getTextAccuracy());assertTrue(q.getExtractionCoverageComplete());assertTrue(VettingCorpus.chunks(Collections.singletonList(d)).isEmpty());
  d.setParseStatus("PARTIAL");q=VettingSourceQuality.from(d,Collections.emptyList());assertEquals("PARTIAL",q.getParseStatus());assertTrue(q.getLimitations().contains("parser_partial_extraction"));
 }
 @Test void qualityChangesIdentityAndCachePayloadButPreservesSourceTextHashOwnerAndOffsets() {
  SourceDocument d=source(7L,"ocr");Chunk old=VettingCorpus.chunks(Collections.singletonList(d)).get(0);String sourceHash=VettingCorpus.sourceHash(d);
  d.setParseStatus("PARTIAL");Chunk next=VettingCorpus.chunks(Collections.singletonList(d)).get(0);
  assertNotEquals(old.getSourceQualityHash(),next.getSourceQualityHash());assertNotEquals(old.getId(),next.getId());assertEquals(sourceHash,next.getSourceHash());
  assertEquals(old.getContent(),next.getContent());assertEquals(old.getClauseId(),next.getClauseId());assertEquals(old.getAnchor(),next.getAnchor());
  assertEquals(old.getParts().get(0).getStartOffset(),next.getParts().get(0).getStartOffset());assertEquals(old.getParts().get(0).getEndOffset(),next.getParts().get(0).getEndOffset());
  assertNotEquals(JsonUtils.write(old),JsonUtils.write(next));
 }
 @Test void coverageOrderUnrelatedTimestampsAndUnsafeFreeTextDoNotPerturbCanonicalQualityHash() {
  SourceDocument d=source(8L,"ocr");String first=VettingSourceQuality.hash(VettingSourceQuality.from(d,VettingCorpus.blocks(d)));
  d.setParseCoverageJson("{\"timestamp\":\"other\",\"complete\":true,\"ocrPages\":1,\"parsedPages\":1,\"totalPages\":1,\"unrelatedInstruction\":\"UNTRUSTED_SECRET_TIMESTAMP\"}");
  VettingSourceQuality.Info q=VettingSourceQuality.from(d,VettingCorpus.blocks(d));assertEquals(first,VettingSourceQuality.hash(q));assertFalse(JsonUtils.write(q).contains("UNTRUSTED_SECRET_TIMESTAMP"));
  d.setParseCoverageJson("{\"complete\":true,\"ocrPages\":1,\"parsedPages\":1,\"totalPages\":1,\"limitations\":[\"UNTRUSTED_LIMITATION_INSTRUCTION\"]}");q=VettingSourceQuality.from(d,VettingCorpus.blocks(d));
  assertTrue(q.getLimitations().contains("parser_reported_limitations_present"));assertFalse(JsonUtils.write(q).contains("UNTRUSTED_LIMITATION_INSTRUCTION"));
 }
 @Test void absentCoverageAndUnseenFilenamesRolesStayUnknownWithoutTemplateInference() {
  SourceDocument d=source(9L,"native");d.setOcrUsed(null);d.setParseStatus(null);d.setParseCoverageJson(null);d.setFileName("NTT fixed template.pdf");d.setReviewRole("project_fact");
  Chunk c=VettingCorpus.chunks(Collections.singletonList(d)).get(0);assertEquals("project_fact",c.getRole());assertEquals("unknown",c.getSourceQuality().getParseStatus());assertEquals("unknown",c.getSourceQuality().getOcrQualityStatus());
  assertTrue(c.getSourceQuality().isQualityPageScopeUnknown());assertNull(c.getSourceQuality().getExtractionCoverageComplete());
  d.setFileName("An entirely new material.pdf");assertEquals(c.getSourceQualityHash(),VettingCorpus.chunks(Collections.singletonList(d)).get(0).getSourceQualityHash());
 }
 @Test void conflictingFlagsCountsAndPhysicalPageIdentitiesRemainNeedsReviewWithUnknownScope() {
  for(String coverage:Arrays.asList("{\"totalPages\":1,\"parsedPages\":2,\"ocrPages\":1,\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":[1],\"ocrQualityPageScopeUnknown\":false}","{\"totalPages\":1,\"parsedPages\":1,\"ocrPages\":4,\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":[1],\"ocrQualityPageScopeUnknown\":false}","{\"totalPages\":2,\"parsedPages\":2,\"ocrPages\":1,\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":[1],\"ocrQualityPageScopeUnknown\":false}")) {
   SourceDocument d=source(10L,"ocr");d.setOcrUsed(false);d.setParseCoverageJson(coverage);VettingSourceQuality.Info q=VettingSourceQuality.from(d,VettingCorpus.blocks(d));
   assertEquals("needs_review",q.getOcrQualityStatus());assertTrue(q.isQualityPageScopeUnknown());
  }
 }
 @Test void sourceQualityScopePreventsContinuationAcrossSameTextRevision() {
  SourceDocument d=source(11L,"ocr");List<DocumentBlock> blocks=VettingCorpus.blocks(d);blocks.get(0).setText("The contractor shall retain. "+String.join("",Collections.nCopies(1420,"x")));d.setTextContent(blocks.get(0).getText());d.setStructuredContentJson(JsonUtils.write(blocks));
  List<Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(d));assertEquals(2,chunks.size());Chunk h=chunks.get(0),tail=chunks.get(1);tail.setSourceQualityHash("different_quality_hash");
  VettingContextBuilder.Selection selected=new VettingContextBuilder(chunks).build("source",Collections.singletonList(h),Collections.emptyMap(),5000);
  assertEquals(Collections.singletonList(h),selected.getChunks());assertEquals("ambiguous_source_block",selected.getBlockContinuations().get(0).getStatus());
  for(Chunk c:chunks)assertEquals("ocr",c.getParts().get(0).getExtractionSource());
 }
 @Test void nativeSelectedHeaderCannotCrossQualityVersionHashOrRoleEvenWithSameSourceText() {
  DocumentBlock h=new DocumentBlock();h.setId("body:2:table-row:0");h.setLocation("body/2/table-row/0");h.setKind("table_row");h.setSource("docx");h.setCells(Arrays.asList("Clause","Required input","Reply"));h.setText(String.join(" | ",h.getCells()));
  DocumentBlock r=new DocumentBlock();r.setId("body:2:table-row:1");r.setLocation("body/2/table-row/1");r.setKind("table_row");r.setSource("docx");r.setCells(Arrays.asList("SCC7","Provide the address","Office 1"));r.setText(String.join(" | ",r.getCells()));
  SourceDocument d=source(14L,"docx");d.setReviewRole("project_fact");d.setOcrUsed(false);d.setParseCoverageJson(null);d.setStructuredContentJson(JsonUtils.write(Arrays.asList(h,r)));d.setTextContent(h.getText()+"\n"+r.getText());
  Chunk complete=VettingCorpus.chunks(Collections.singletonList(d)).get(0);
  for(String fault:Arrays.asList("none","hash","version","role")) {
   Chunk header=JsonUtils.read(JsonUtils.write(complete),Chunk.class),body=JsonUtils.read(JsonUtils.write(complete),Chunk.class);header.setId("selected-header");body.setId("selected-body");
   header.setParts(Collections.singletonList(header.getParts().get(0)));header.setContent(h.getText());header.setAnchor(header.getParts().get(0).getAnchor());
   body.setParts(Collections.singletonList(body.getParts().get(1)));body.setContent(r.getText());body.setAnchor(body.getParts().get(0).getAnchor());
   if("hash".equals(fault))header.setSourceQualityHash("another_quality_hash");if("version".equals(fault))header.setSourceQualityMetadataVersion("another_quality_version");if("role".equals(fault))header.setRole("standard");
   VettingProjectFactTable.Result result=VettingSourceMaterial.projectFactRows(Arrays.asList(header,body),Collections.singletonList("SCC7"));
   assertEquals("none".equals(fault)?1:0,result.getRows().size(),fault);
  }
 }
 @Test void changingQualityAfterCorpusCreationCannotAssertVerifiedOrHideNeedsReview() {
  Chunk c=VettingCorpus.chunks(Collections.singletonList(source(12L,"ocr"))).get(0);c.getSourceQuality().setOcrQualityStatus("verified");c.setSourceQualityHash(VettingSourceQuality.hash(c.getSourceQuality()));
  assertEquals("unknown",VettingSourceQuality.project(c).get("ocrQualityStatus"));assertEquals("unverified",VettingSourceQuality.project(c).get("textAccuracy"));
  c.setSourceQualityMetadataVersion("old_quality");assertEquals("unknown",VettingSourceQuality.project(c).get("parseStatus"));
 }
 @Test void selectedPartMethodProjectionDoesNotExposeUnselectedPartsOrGuessMissingSource() {
  Chunk c=VettingCorpus.chunks(Collections.singletonList(source(13L,"ocr"))).get(0);String before=JsonUtils.write(c);
  Map<String,Object> e=VettingSourceMaterial.project(Collections.singletonList(c)).get(0);List<?> parts=(List<?>)e.get("selectedPartExtractionObservations");assertEquals(1,parts.size());
  Map<?,?> part=(Map<?,?>)parts.get(0);assertEquals(c.getParts().get(0).getAnchor(),part.get("partAnchor"));assertEquals(c.getParts().get(0).getEndOffset(),part.get("endOffset"));assertEquals("ocr",part.get("extractionSource"));
  assertEquals(before,JsonUtils.write(c));c.getParts().get(0).setExtractionSource(null);e=VettingSourceMaterial.project(Collections.singletonList(c)).get(0);
  assertEquals("unknown",((Map<?,?>)((List<?>)e.get("selectedPartExtractionObservations")).get(0)).get("extractionSource"));
 }
 @Test void reviewPageCountConflictInEitherDirectionKeepsScopeUnknownWithoutInventingPages() {
  for(int declaredCount:Arrays.asList(1,0,3)) {
   SourceDocument d=source(15L,"ocr");d.setPageCount(3);
   d.setParseCoverageJson("{\"totalPages\":3,\"parsedPages\":3,\"ocrPages\":"+declaredCount+",\"complete\":true,\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":[1,2],\"ocrQualityPageScopeUnknown\":false}");
   VettingSourceQuality.Info q=VettingSourceQuality.from(d,VettingCorpus.blocks(d));
   assertEquals(Arrays.asList(1,2),q.getNeedsReviewPages());assertEquals("needs_review",q.getOcrQualityStatus());
   assertTrue(q.isQualityPageScopeUnknown(),"explicit OCR page count="+declaredCount+" conflicts with two distinct listed OCR review pages");
   assertEquals("unverified",q.getTextAccuracy());assertTrue(q.getExtractionCoverageComplete());
  }
  SourceDocument equal=source(16L,"ocr");equal.setPageCount(3);
  equal.setParseCoverageJson("{\"totalPages\":3,\"parsedPages\":3,\"ocrPages\":2,\"complete\":true,\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":[2,1,1],\"ocrQualityPageScopeUnknown\":false}");
  VettingSourceQuality.Info q=VettingSourceQuality.from(equal,VettingCorpus.blocks(equal));
  assertEquals(Arrays.asList(1,2),q.getNeedsReviewPages());assertFalse(q.isQualityPageScopeUnknown());
 }
 @Test void allMandatoryOcrQualityFieldsMustBeSuccessfullyRecognizedBeforePageScopeIsKnown() {
  for(String fields:Arrays.asList(
    "\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":[1],\"ocrQualityPageScopeUnknown\":null",
    "\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":[1],\"ocrQualityPageScopeUnknown\":\"false\"",
    "\"ocrQualityStatus\":\"verified\",\"needsReviewPages\":[1],\"ocrQualityPageScopeUnknown\":false",
    "\"ocrQualityStatus\":null,\"needsReviewPages\":[1],\"ocrQualityPageScopeUnknown\":false",
    "\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":null,\"ocrQualityPageScopeUnknown\":false",
    "\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":[\"1\"],\"ocrQualityPageScopeUnknown\":false")) {
   SourceDocument d=source(17L,"ocr");d.setParseCoverageJson("{\"totalPages\":1,\"parsedPages\":1,\"ocrPages\":1,\"complete\":true,"+fields+"}");
   VettingSourceQuality.Info q=VettingSourceQuality.from(d,VettingCorpus.blocks(d));
   assertEquals("needs_review",q.getOcrQualityStatus());assertEquals("unverified",q.getTextAccuracy());assertTrue(q.isQualityPageScopeUnknown(),fields);
  }
 }
 @Test void observedOcrExtractionPageConflictDoesNotExpandOrCertifyDeclaredReviewPages() {
  SourceDocument d=source(18L,"ocr");d.setPageCount(2);
  d.setParseCoverageJson("{\"totalPages\":2,\"parsedPages\":2,\"ocrPages\":1,\"complete\":true,\"ocrQualityStatus\":\"needs_review\",\"needsReviewPages\":[1],\"ocrQualityPageScopeUnknown\":false}");
  List<DocumentBlock> blocks=VettingCorpus.blocks(d);blocks.get(0).setPageNo(2);
  VettingSourceQuality.Info q=VettingSourceQuality.from(d,blocks);
  assertEquals(Collections.singletonList(1),q.getNeedsReviewPages());assertEquals("needs_review",q.getOcrQualityStatus());assertEquals("unverified",q.getTextAccuracy());
  assertTrue(q.isQualityPageScopeUnknown());assertTrue(q.getLimitations().contains("ocr_extraction_page_not_in_review_declaration"));
  blocks.get(0).setPageNo(1);q=VettingSourceQuality.from(d,blocks);assertFalse(q.isQualityPageScopeUnknown());
  assertFalse(q.getLimitations().contains("ocr_extraction_page_not_in_review_declaration"));
 }
 @Test void nativeNoOcrNullableQualityFieldsDoNotCreateAnOcrConflictOrPerturbQualityIdentity() {
  SourceDocument d=source(19L,"docx");d.setOcrUsed(false);
  d.setParseCoverageJson("{\"totalPages\":1,\"parsedPages\":1,\"ocrPages\":0,\"complete\":true}");
  VettingSourceQuality.Info old=VettingSourceQuality.from(d,VettingCorpus.blocks(d));
  d.setParseCoverageJson("{\"totalPages\":1,\"parsedPages\":1,\"ocrPages\":0,\"complete\":true,\"ocrQualityStatus\":null,\"needsReviewPages\":[],\"ocrQualityPageScopeUnknown\":false}");
  VettingSourceQuality.Info q=VettingSourceQuality.from(d,VettingCorpus.blocks(d));
  assertEquals("not_observed",q.getOcrQualityStatus());assertFalse(q.isQualityPageScopeUnknown());assertEquals("unverified",q.getTextAccuracy());
  assertEquals(VettingSourceQuality.hash(old),VettingSourceQuality.hash(q));assertFalse(q.getLimitations().contains("ocr_quality_declaration_fields_unknown"));
 }
 private static SourceDocument source(Long id,String method) {
  DocumentBlock b=new DocumentBlock();b.setId("pdf-page:1:line:0");b.setLocation("pdf-page/1/line/0");b.setKind("ocr_line");b.setSource(method);b.setPageNo(1);b.setText("The new site attachment requires original-page review 😀.");
  SourceDocument d=new SourceDocument();d.setId(id);d.setReviewRole("tender");d.setFileKey("UNSEEN");d.setFileName("New competition attachment.pdf");d.setParseStatus("PARSED");d.setOcrUsed(true);d.setPageCount(1);
  d.setParseCoverageJson("{\"totalPages\":1,\"parsedPages\":1,\"ocrPages\":1,\"complete\":true}");d.setStructuredContentJson(JsonUtils.write(Collections.singletonList(b)));d.setTextContent(b.getText());return d;
 }
}
