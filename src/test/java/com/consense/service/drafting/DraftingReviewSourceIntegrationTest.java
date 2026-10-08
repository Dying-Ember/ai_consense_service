package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.w3c.dom.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Review F3/F4/F5: actual standard packages through public upload/plan/generation/export. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingReviewSourceIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String M="http://schemas.openxmlformats.org/officeDocument/2006/math";
    private static final String DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String[] KEYS={"NTT","SCT","SCC"};
    private static final String[] NAMES={"01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx"};
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:draft_review_source_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","drafting-review-source-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @SuppressWarnings("unchecked") String project()throws Exception {
        String id="source-review-"+UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(DraftBusinessRules.map("id",id,"nameZhHans","独立审查机械测试","nameEn","Independent source review test")))).andExpect(jsonPath("$.code").value(0));
        for(int i=0;i<3;i++)upload(id,KEYS[i],NAMES[i],original(i));
        return id;
    }
    byte[] original(int i)throws IOException {String path=System.getProperty("consense.acceptance.sourceDir");assumeTrue(path!=null,"Actual standard sourceDir required");return Files.readAllBytes(Paths.get(path,NAMES[i]));}
    void upload(String id,String key,String name,byte[] bytes)throws Exception {mvc.perform(multipart("/api/drafting/{id}/templates/{key}/replace",id,key).file(new MockMultipartFile("file",name,DOCX,bytes))).andExpect(jsonPath("$.data.parsed").value(1));}
    void value(String id,String key,String value)throws Exception {mvc.perform(put("/api/drafting/{id}/variables/{key}",id,key).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(Collections.singletonMap("value",value)))).andExpect(jsonPath("$.code").value(0));}
    @SuppressWarnings("unchecked") Map<String,Object> plan(String id)throws Exception {return (Map<String,Object>)JsonUtils.readMap(mvc.perform(get("/api/drafting/{id}/plan",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).get("data");}
    @SuppressWarnings("unchecked") Map<String,Object> generate(String id,int document)throws Exception {List<Map<String,Object>> docs=(List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).get("data");return docs.get(document);}
    byte[] word(String id,String key)throws Exception {byte[] bytes=mvc.perform(get("/api/drafting/{id}/documents/{key}/export.docx",id,key)).andExpect(content().contentTypeCompatibleWith(DOCX)).andReturn().getResponse().getContentAsByteArray();String dir=System.getProperty("consense.acceptance.artifactEvidence");if(dir!=null){Files.createDirectories(Paths.get(dir));Path file=Paths.get(dir,key+"-"+DraftPdfConverter.sha256(bytes)+".docx");if(!Files.exists(file))Files.write(file,bytes,StandardOpenOption.CREATE_NEW);}return bytes;}
    @SuppressWarnings("unchecked") static Map<String,Object> action(Map<String,Object> plan,String id) {return ((List<Map<String,Object>>)plan.get("actions")).stream().filter(a->id.equals(a.get("id"))).findFirst().get();}
    static Map<String,byte[]> parts(byte[] bytes)throws IOException {Map<String,byte[]> result=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;byte[] buffer=new byte[8192];while((e=zip.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();int n;while((n=zip.read(buffer))!=-1)out.write(buffer,0,n);result.put(e.getName(),out.toByteArray());}}return result;}
    static void opaquePartsIdentical(byte[] original,byte[] output)throws IOException {Map<String,byte[]> a=parts(original),b=parts(output);assertEquals(a.keySet(),b.keySet());for(String part:a.keySet())if(!"word/document.xml".equals(part))assertArrayEquals(a.get(part),b.get(part),part);}
    static Document xml(byte[] bytes)throws Exception {DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);return f.newDocumentBuilder().parse(new ByteArrayInputStream(parts(bytes).get("word/document.xml")));}
    static String text(Node paragraph){StringBuilder out=new StringBuilder();NodeList texts=((Element)paragraph).getElementsByTagNameNS(W,"t");for(int i=0;i<texts.getLength();i++)out.append(texts.item(i).getTextContent());return out.toString();}
    static Node paragraph(Document doc,String exact){NodeList nodes=doc.getElementsByTagNameNS(W,"p");for(int i=0;i<nodes.getLength();i++)if(exact.equals(text(nodes.item(i))))return nodes.item(i);throw new AssertionError("Missing source paragraph: "+exact);}
    static void sourceParagraphIdentical(Document before,Document after,int ordinal){Node p=before.getElementsByTagNameNS(W,"p").item(ordinal-1);NodeList ps=after.getElementsByTagNameNS(W,"p");boolean found=false;for(int i=0;i<ps.getLength();i++)if(text(p).equals(text(ps.item(i)))&&p.isEqualNode(withoutGeneratedBindings(before,ps.item(i))))found=true;assertTrue(found,"Unrelated native paragraph P"+ordinal+" including formatting");}
    /** Ignore only complete new ConSense marker pairs; all original bookmarks and native content still compare. */
    static Node withoutGeneratedBindings(Document source,Node emitted) {
        Node normalized=emitted.cloneNode(true);Set<String> sourceNames=new HashSet<>(),sourceIds=new HashSet<>();
        NodeList originalStarts=source.getElementsByTagNameNS(W,"bookmarkStart"),originalEnds=source.getElementsByTagNameNS(W,"bookmarkEnd");
        for(int i=0;i<originalStarts.getLength();i++){Element start=(Element)originalStarts.item(i);sourceNames.add(start.getAttributeNS(W,"name"));sourceIds.add(start.getAttributeNS(W,"id"));}
        for(int i=0;i<originalEnds.getLength();i++)sourceIds.add(((Element)originalEnds.item(i)).getAttributeNS(W,"id"));
        NodeList starts=((Element)normalized).getElementsByTagNameNS(W,"bookmarkStart"),ends=((Element)normalized).getElementsByTagNameNS(W,"bookmarkEnd");List<Node> remove=new ArrayList<>();
        for(int i=0;i<starts.getLength();i++) {
            Element start=(Element)starts.item(i);String name=start.getAttributeNS(W,"name"),id=start.getAttributeNS(W,"id");
            if(start.getParentNode()!=normalized||!name.matches("CS[a-f0-9]{28}")||id.isEmpty()||sourceNames.contains(name)||sourceIds.contains(id))continue;
            int sameStarts=0;List<Node> matchingEnds=new ArrayList<>();
            for(int n=0;n<starts.getLength();n++)if(id.equals(((Element)starts.item(n)).getAttributeNS(W,"id")))sameStarts++;
            for(int n=0;n<ends.getLength();n++)if(id.equals(((Element)ends.item(n)).getAttributeNS(W,"id")))matchingEnds.add(ends.item(n));
            if(sameStarts==1&&matchingEnds.size()==1&&matchingEnds.get(0).getParentNode()==normalized){remove.add(start);remove.add(matchingEnds.get(0));}
        }
        for(Node marker:remove)normalized.removeChild(marker);
        return normalized;
    }
    static int fluctuationTables(Document doc){NodeList tables=doc.getElementsByTagNameNS(W,"tbl");int count=0;for(int i=0;i<tables.getLength();i++){NodeList texts=((Element)tables.item(i)).getElementsByTagNameNS(W,"t");StringBuilder content=new StringBuilder();for(int n=0;n<texts.getLength();n++)content.append(texts.item(n).getTextContent());if(content.toString().contains("LF = LC x EV x")||content.toString().contains("MF = MC x EV x"))count++;}return count;}
    static boolean hasHeading(Document doc,String clause){NodeList ps=doc.getElementsByTagNameNS(W,"p");for(int i=0;i<ps.getLength();i++){String title=text(ps.item(i)).trim();if(title.startsWith(clause)&&!title.matches(".*\\d+$"))return true;}return false;}

    @Test void explicitSpecialistNegativesRemoveCompleteNativeScopesAndKeepUnrelatedFormulae()throws Exception {
        byte[] source=original(2);Document before=xml(source);assertEquals(4,before.getElementsByTagNameNS(M,"oMath").getLength());
        for(String decision:Arrays.asList("not_used","delete")) {
            String id=project();value(id,"subcontractArrangement","BSSSC");value(id,"subcontractors","[\"Electrical\"]");
            value(id,"targetOverrides",JsonUtils.write(Arrays.asList(DraftBusinessRules.map("actionId","scc-specialist-SCC20.303","action",decision),DraftBusinessRules.map("actionId","scc-specialist-SCC20.304","action",decision))));
            Map<String,Object> preview=plan(id);
            for(String clause:Arrays.asList("SCC20.303","SCC20.304"))assertEquals("applied",action(preview,"scc-specialist-"+clause).get("applicationState"),"Explicit "+decision+" must apply complete formula-bearing "+clause);
            assertFalse(String.valueOf(action(preview,"scc-specialist-SCC20.304").get("sourceText")).replaceAll("(?U)\\s+","").contains("SCC22GENERAL"),"Public target source scope ends before the following SCC22 chapter heading");
            Map<String,Object> generated=generate(id,2);byte[] emitted=word(id,"SCC");Document after=xml(emitted);
            assertEquals(2,after.getElementsByTagNameNS(M,"oMath").getLength(),"Only the two removed clauses' equations disappear");
            for(int i=0;i<2;i++)assertTrue(before.getElementsByTagNameNS(M,"oMath").item(i).isEqualNode(after.getElementsByTagNameNS(M,"oMath").item(i)),"Unrelated native equation "+i+" is untouched");
            assertEquals(3,fluctuationTables(before),"Original specialists have one combined labour/material table per clause");
            assertEquals(1,fluctuationTables(after),"Only the unrelated electrical labour/material table survives");
            for(int p:Arrays.asList(1433,1666,1779,1781))sourceParagraphIdentical(before,after,p);
            String content=String.valueOf(generated.get("content")).replaceAll("(?U)\\s+"," ");
            for(int p:Arrays.asList(1550,1665)){String originalTitle=text(before.getElementsByTagNameNS(W,"p").item(p-1));NodeList ps=after.getElementsByTagNameNS(W,"p");for(int i=0;i<ps.getLength();i++)assertNotEquals(originalTitle,text(ps.item(i)),"Original operative heading was removed/replaced; cached native TOC fields remain separately unrefreshed");}
            for(String clause:Arrays.asList("SCC20.303","SCC20.304")){if("not_used".equals(decision))assertTrue(content.contains(clause+" Not used"));else assertFalse(hasHeading(after,clause),"Deleted clause has no surviving heading");}
            opaquePartsIdentical(source,emitted);
        }
    }

    @Test void advancePaymentSingularReferencesSynchronizeWhileAllFormulaClausesAreRetained()throws Exception {
        String id=project();value(id,"subcontractArrangement","BSSSC");value(id,"subcontractors","[\"Electrical\"]");value(id,"advancePaymentAdopted","true");
        // Mechanical reference isolation only: explicit retention avoids F3 negative formula removal.
        value(id,"targetOverrides",JsonUtils.write(Arrays.asList(DraftBusinessRules.map("actionId","scc-specialist-SCC20.303","action","retain"),DraftBusinessRules.map("actionId","scc-specialist-SCC20.304","action","retain"))));
        assertEquals("applied",action(plan(id),"scc-advance-fluctuation-references").get("applicationState"),"The actual P1216 repeated singular Clause collection must match");
        generate(id,2);byte[] bytes=word(id,"SCC");Document before=xml(original(2)),after=xml(bytes);
        String originalPayment=text(before.getElementsByTagNameNS(W,"p").item(1215));
        String expected=originalPayment.replace("Clause SCC20.304 of the Special Conditions of Contract*.","Clause SCC20.304 of the Special Conditions of Contract.");
        assertNotEquals(originalPayment,expected);assertNotNull(paragraph(after,expected),"Exact payment wording apart from the applicable instruction mark");
        assertEquals(4,after.getElementsByTagNameNS(M,"oMath").getLength());
        for(int i=0;i<4;i++)assertTrue(before.getElementsByTagNameNS(M,"oMath").item(i).isEqualNode(after.getElementsByTagNameNS(M,"oMath").item(i)));
        opaquePartsIdentical(original(2),bytes);
    }

    @Test void explicitWtoNegativeUsesVerifiedClauseAndDistinguishesNotUsedFromDelete()throws Exception {
        String ordinary=project();value(ordinary,"wtoGpaApplies","false");generate(ordinary,0);byte[] ordinaryNotUsed=word(ordinary,"NTT");
        ntt9NotUsedHasCleanNativeNumber(original(0),ordinaryNotUsed);
        for(String decision:Arrays.asList("not_used","delete")) {
            String id=project();value(id,"targetOverrides",JsonUtils.write(Collections.singletonList(DraftBusinessRules.map("actionId","NTT-9-WTO","action",decision))));
            assertEquals("applied",action(plan(id),"NTT-9-WTO").get("applicationState"),"The catalogue NTT 9(a)–(d) target has a verified whole-clause negative mapping");
            Map<String,Object> output=generate(id,0);byte[] emitted=word(id,"NTT");DocxTemplateEditor.TemplateIndex index=new DocxTemplateEditor().inspect(emitted);
            assertFalse(String.valueOf(output.get("content")).contains("This tender is covered by the Agreement on Government Procurement"));
            if("not_used".equals(decision)){ntt9NotUsedHasCleanNativeNumber(original(0),emitted);assertArrayEquals(ordinaryNotUsed,emitted,"Direct Not used has the same native package output as ordinary WTO=false");}
            else {assertFalse(index.getMainParagraphs().stream().anyMatch(p->p.getCatalogText().trim().matches("\\*?9\\.")),"Delete removes the source clause number rather than leaving 9. Not used or an empty heading");assertFalse(String.valueOf(output.get("content")).contains("9. Not used"));}
            opaquePartsIdentical(original(0),emitted);sourceParagraphIdentical(xml(original(0)),xml(emitted),484);
        }
    }

    static void ntt9NotUsedHasCleanNativeNumber(byte[] original,byte[] output)throws Exception {
        Document before=xml(original),after=xml(output);
        Node expected=before.getElementsByTagNameNS(W,"p").item(434).cloneNode(true);
        assertEquals("*9.",text(expected),"Actual source P435 is the optional NTT9 number cell");
        ((Element)expected).getElementsByTagNameNS(W,"t").item(0).setTextContent("9.");
        Node actual=paragraph(after,"9.");
        assertTrue(expected.isEqualNode(withoutGeneratedBindings(before,actual)),"Only the editing star changes; native number runs, styles, tabs and cell paragraph remain intact");
        Node row=actual.getParentNode().getParentNode();assertEquals("tr",row.getLocalName());
        NodeList cells=((Element)row).getElementsByTagNameNS(W,"tc");assertEquals(3,cells.getLength());
        assertEquals("Not used",text(cells.item(1)),"Not used remains in the adjacent native title cell");
        assertEquals("",text(cells.item(2)),"The WTO drafting instruction is cleared");
        sourceParagraphIdentical(before,after,243); // The actual unrelated Works Subject to Excision star remains unchanged.
    }

    @Test void survivingElectricalClauseUpdatesBothReferenceCollectionsAndKeepsPaymentWording()throws Exception {
        String id=project();value(id,"subcontractArrangement","BSSSC");value(id,"subcontractors","[\"Electrical\"]");value(id,"advancePaymentAdopted","true");
        value(id,"targetOverrides",JsonUtils.write(Arrays.asList(DraftBusinessRules.map("actionId","scc-specialist-SCC20.303","action","delete"),DraftBusinessRules.map("actionId","scc-specialist-SCC20.304","action","not_used"))));
        Map<String,Object> planned=plan(id);assertEquals(Collections.singletonList("SCC20.302"),((Map<?,?>)planned.get("derived")).get("specialistFluctuationReferences"));
        for(String key:Arrays.asList("scc-general-fluctuation-references","scc-advance-fluctuation-references"))assertEquals("applied",action(planned,key).get("applicationState"));
        generate(id,2);byte[] output=word(id,"SCC");Document before=xml(original(2)),after=xml(output);
        String payment=text(before.getElementsByTagNameNS(W,"p").item(1215));
        String expected=payment.replace(", Clause SCC20.302, Clause SCC20.303 and Clause SCC20.304 of the Special Conditions of Contract*",", Clause SCC20.302 of the Special Conditions of Contract");
        assertNotEquals(payment,expected);assertNotNull(paragraph(after,expected),"Complete surrounding payment wording and both GCC references stay intact");
        String general=text(before.getElementsByTagNameNS(W,"p").item(1429));
        String expectedGeneral=general.replace("Clauses SCC20.302, SCC20.303 and SCC20.304 of the Special Conditions of Contract#","Clauses SCC20.302 of the Special Conditions of Contract");
        assertNotEquals(general,expectedGeneral);assertNotNull(paragraph(after,expectedGeneral),"The existing plural reference collection also follows surviving clauses");
        assertEquals(2,after.getElementsByTagNameNS(M,"oMath").getLength());opaquePartsIdentical(original(2),output);
    }

    @Test void explicitNegativeOnUnverifiedPackageEditionPreservesItsClauseAndNativeControls()throws Exception {
        for(int document:Arrays.asList(0,2)) {
            String id=project();byte[] source=original(document);Map<String,byte[]> entries=parts(source);
            String styles=new String(entries.get("word/styles.xml"),java.nio.charset.StandardCharsets.UTF_8);assertTrue(styles.contains("Times New Roman"));
            entries.put("word/styles.xml",styles.replace("Times New Roman","Georgia").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteArrayOutputStream packed=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(packed)){for(Map.Entry<String,byte[]> entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
            byte[] changed=packed.toByteArray();assertNotEquals(DraftPdfConverter.sha256(source),DraftPdfConverter.sha256(changed));
            upload(id,KEYS[document],NAMES[document],changed);
            String target=document==0?"NTT-9-WTO":"scc-specialist-SCC20.304";
            value(id,"targetOverrides",JsonUtils.write(Collections.singletonList(DraftBusinessRules.map("actionId",target,"action","not_used"))));
            Map<String,Object> output=generate(id,document);assertTrue(JsonUtils.write(output.get("unresolved")).contains("SOURCE_STRUCTURAL_CLAUSE_EDITION_UNVERIFIED"),"An explicit decision does not certify an unreviewed DOCX edition");
            byte[] emitted=word(id,KEYS[document]);Document before=xml(changed),after=xml(emitted);
            for(int p:document==0?Arrays.asList(435,438,484):Arrays.asList(1665,1675,1779))sourceParagraphIdentical(before,after,p);
            assertEquals(before.getElementsByTagNameNS(M,"oMath").getLength(),after.getElementsByTagNameNS(M,"oMath").getLength());
            for(int i=0;i<before.getElementsByTagNameNS(M,"oMath").getLength();i++)assertTrue(before.getElementsByTagNameNS(M,"oMath").item(i).isEqualNode(after.getElementsByTagNameNS(M,"oMath").item(i)));
            opaquePartsIdentical(changed,emitted);
        }
    }

    @Test void adoptedWaterproofWarrantyInstallationsReachAllNativeClausePositions()throws Exception {
        String id=project();
        for(String key:Arrays.asList("roofingInstallationIncluded","roofingWarrantyRequired","refugeFloorInstallationIncluded","refugeFloorWarrantyRequired","otherWaterproofingWarrantyRequired"))value(id,key,"true");
        value(id,"otherWaterproofingSpecificationAreas","Kitchens and bathrooms");
        Map<String,Object> selected=action(plan(id),"scc-waterproof-warranty");
        assertEquals(Arrays.asList("Roofing Installation","Refuge Floor Installation","Waterproofing System Installation"),selected.get("value"));
        assertEquals("applied",selected.get("applicationState"),String.valueOf(selected.get("application")));
        generate(id,2);byte[] output=word(id,"SCC");Document before=xml(original(2)),after=xml(output);
        for(int p:Arrays.asList(608,610,612,618,620,622)) {
            String originalText=text(before.getElementsByTagNameNS(W,"p").item(p-1));
            assertTrue(originalText.contains("#"));
            String expected=originalText.replace("#","");
            if(p==610)expected=expected.replace(", and",",");
            if(p==618||p==620)expected=expected.replace("; and",";");
            Node applied=paragraph(after,expected);
            assertEquals(((Element)before.getElementsByTagNameNS(W,"p").item(p-1)).getElementsByTagNameNS(W,"tab").getLength(),((Element)applied).getElementsByTagNameNS(W,"tab").getLength(),"Original native tab at P"+p);
        }
        assertFalse(text(after.getDocumentElement()).contains("Roofing Installation#"));
        opaquePartsIdentical(original(2),output);
    }

    @Test void changedWarrantyTextCannotBeHiddenByWhitespaceTolerantSourceMatching()throws Exception {
        String id=project();byte[] source=original(2);Map<String,byte[]> entries=parts(source);
        String body=new String(entries.get("word/document.xml"),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("the roofing installation including that of the waterproofing membrane"));
        entries.put("word/document.xml",body.replace("the roofing installation including that of the waterproofing membrane","the roofing installation EXCLUDING the waterproofing membrane").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ByteArrayOutputStream packed=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(packed)){for(Map.Entry<String,byte[]> entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
        byte[] changed=packed.toByteArray();upload(id,"SCC",NAMES[2],changed);
        for(String key:Arrays.asList("roofingInstallationIncluded","roofingWarrantyRequired"))value(id,key,"true");
        for(String key:Arrays.asList("refugeFloorInstallationIncluded","refugeFloorWarrantyRequired","otherWaterproofingWarrantyRequired"))value(id,key,"false");
        Map<String,Object> selected=action(plan(id),"scc-waterproof-warranty");
        assertEquals("unresolved",selected.get("applicationState"));
        generate(id,2);byte[] output=word(id,"SCC");
        assertNotNull(paragraph(xml(output),text(xml(changed).getElementsByTagNameNS(W,"p").item(607))),"Changed project wording is retained for explicit human target mapping");
        opaquePartsIdentical(changed,output);
    }
}
