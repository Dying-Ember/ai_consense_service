package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual template upload, guarded body save, bindings and Word export HTTP seam. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class DraftingMultilineBodyIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String[] KEYS={"NTT","SCT","SCC"};
    private static final String[] NAMES={"01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx"};
    private static final String BROWSER_NOTE="TEST ONLY — browser body-edit and revision acceptance; not a contractual instruction.";
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:draft_multiline_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","drafting-multiline-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @SuppressWarnings("unchecked") private static Map<String,Object> data(String response){return (Map<String,Object>)JsonUtils.readMap(response).get("data");}
    private String project()throws Exception {
        String id="multiline-"+UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(DraftBusinessRules.map("id",id,"nameZhHans","TEST ONLY 正文多行保存","nameEn","TEST ONLY multiline body save")))).andExpect(jsonPath("$.code").value(0));
        String sourceDir=System.getProperty("consense.acceptance.sourceDir");assertNotNull(sourceDir,"Explicit actual original templates required");
        for(int i=0;i<KEYS.length;i++)mvc.perform(multipart("/api/drafting/{id}/templates/{key}/replace",id,KEYS[i]).file(new MockMultipartFile("file",NAMES[i],DOCX,Files.readAllBytes(Paths.get(sourceDir,NAMES[i]))))).andExpect(jsonPath("$.data.parsed").value(1));
        return id;
    }
    private Map<String,Object> document(String id)throws Exception {return document(id,0);}
    @SuppressWarnings("unchecked") private Map<String,Object> document(String id,int index)throws Exception {return ((List<Map<String,Object>>)JsonUtils.readMap(mvc.perform(get("/api/drafting/{id}/documents",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).get("data")).get(index);}
    @SuppressWarnings("unchecked") private static Map<String,Object> paragraph(Map<String,Object> doc,int ordinal){return ((List<Map<String,Object>>)doc.get("blocks")).stream().filter(b->Integer.valueOf(ordinal).equals(b.get("paragraphOrdinal"))).findFirst().get();}
    private byte[] word(String id)throws Exception {return word(id,"NTT");}
    private byte[] word(String id,String key)throws Exception {return mvc.perform(get("/api/drafting/{id}/documents/{key}/export.docx",id,key)).andExpect(content().contentTypeCompatibleWith(DOCX)).andReturn().getResponse().getContentAsByteArray();}
    private void evidence(String scenario,String name,byte[] bytes)throws IOException {String path=System.getProperty("consense.acceptance.bodyEvidence");if(path!=null){Path out=Paths.get(path,scenario);Files.createDirectories(out);Files.write(out.resolve(name),bytes,StandardOpenOption.CREATE_NEW);}}
    private void json(String scenario,String name,Object value)throws IOException {evidence(scenario,name,JsonUtils.write(value).getBytes(StandardCharsets.UTF_8));}

    @Test void browserNewlineBodySaveRetainsOriginalNativeFormattingAndMatchingRevision()throws Exception {
        assertNativeBodySave("browser-lf","\n"+BROWSER_NOTE,1,0);
    }
    @Test void browserTabBodySaveRetainsOriginalNativeFormattingAndMatchingRevision()throws Exception {
        assertNativeBodySave("browser-tab","\tTEST ONLY tab-separated continuation.",0,1);
    }
    @Test void pairedCrLfBodyInputNormalizesWithoutDuplicatingExistingNativeBreaks()throws Exception {
        assertNativeBodySave("paired-crlf","\nTEST ONLY paired-line-ending continuation.",1,0,true);
    }
    private void assertNativeBodySave(String scenario,String addition,int breaks,int tabs)throws Exception {
        assertNativeBodySave(scenario,addition,breaks,tabs,false);
    }
    private void assertNativeBodySave(String scenario,String addition,int breaks,int tabs,boolean pairedCrLf)throws Exception {
        String id=project();
        mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0));
        Map<String,Object> before=document(id),block=paragraph(before,546);assertEquals(true,block.get("editable"));assertNotNull(block.get("bindingId"));
        if(pairedCrLf) {
            Map<String,Object> preparation=DraftBusinessRules.map("revisionId",before.get("revisionId"),"docxSha256",before.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",block.get("id"),"bindingId",block.get("bindingId"),"expectedTextHash",block.get("textHash"),"text",block.get("text")+"\nTEST ONLY existing native line.\tTEST ONLY existing native tab.")));
            json(scenario,"preparation-request.json",preparation);String prepared=mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(preparation))).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();json(scenario,"preparation-response.json",JsonUtils.readMap(prepared));before=document(id);block=paragraph(before,546);
        }
        String expected=String.valueOf(block.get("text"))+addition;
        Map<String,Object> request=DraftBusinessRules.map("revisionId",before.get("revisionId"),"docxSha256",before.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",block.get("id"),"bindingId",block.get("bindingId"),"expectedTextHash",block.get("textHash"),"text",pairedCrLf?expected.replace("\n","\r\n"):expected)));
        byte[] oldWord=word(id);json(scenario,"document-before.json",before);json(scenario,"save-request.json",request);evidence(scenario,"NTT-before.docx",oldWord);
        for(int i=0;i<KEYS.length;i++)evidence(scenario,KEYS[i]+"-source.docx",mvc.perform(get("/api/drafting/{id}/templates/{key}/source",id,KEYS[i])).andReturn().getResponse().getContentAsByteArray());
        String response=mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(request))).andReturn().getResponse().getContentAsString();
        Map<String,Object> after=document(id);byte[] newWord=word(id);json(scenario,"save-response.json",JsonUtils.readMap(response));json(scenario,"document-after.json",after);evidence(scenario,"NTT-after.docx",newWord);
        assertEquals(0,JsonUtils.readMap(response).get("code"),response);
        assertEquals(expected,paragraph(after,546).get("text"));assertNotEquals(before.get("revisionId"),after.get("revisionId"));assertEquals(DraftPdfConverter.sha256(newWord),after.get("docxSha256"));assertEquals(before.get("sourceSha256"),after.get("sourceSha256"));
        Map<String,Object> bindings=data(mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id).param("revisionId",String.valueOf(after.get("revisionId"))).param("docxSha256",String.valueOf(after.get("docxSha256")))).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());json(scenario,"bindings-after.json",bindings);
        Object bindingId=block.get("bindingId");@SuppressWarnings("unchecked") Map<String,Object> bound=((List<Map<String,Object>>)bindings.get("bindings")).stream().filter(b->bindingId.equals(b.get("bindingId"))).findFirst().get();assertEquals("exact",bound.get("locationStatus"));assertEquals("body_edited",bound.get("applicationStatus"));assertEquals(expected,bound.get("text"));
        mvc.perform(get("/api/drafting/{id}/documents/NTT/bindings",id).param("revisionId",String.valueOf(before.get("revisionId")))).andExpect(jsonPath("$.code").value(4090));
        Map<String,byte[]> a=parts(oldWord),b=parts(newWord);assertEquals(a.keySet(),b.keySet());for(String part:a.keySet())if(!"word/document.xml".equals(part))assertArrayEquals(a.get(part),b.get(part),part);
        Document oldXml=xml(a.get("word/document.xml")),newXml=xml(b.get("word/document.xml"));NodeList oldPs=oldXml.getElementsByTagNameNS(W,"p"),newPs=newXml.getElementsByTagNameNS(W,"p");assertEquals(oldPs.getLength(),newPs.getLength());
        for(int i=0;i<oldPs.getLength();i++)if(i!=545)assertTrue(oldPs.item(i).isEqualNode(newPs.item(i)),"Unrelated original paragraph "+(i+1));
        Element oldP=(Element)oldPs.item(545),newP=(Element)newPs.item(545);assertEquals(oldP.getElementsByTagNameNS(W,"br").getLength()+breaks,newP.getElementsByTagNameNS(W,"br").getLength());assertEquals(oldP.getElementsByTagNameNS(W,"tab").getLength()+tabs,newP.getElementsByTagNameNS(W,"tab").getLength());
        for(String kind:Arrays.asList("pPr","rPr","bookmarkStart","bookmarkEnd","hyperlink","fldChar","instrText","fldSimple","sectPr")){NodeList oldNodes=oldXml.getElementsByTagNameNS(W,kind),newNodes=newXml.getElementsByTagNameNS(W,kind);assertEquals(oldNodes.getLength(),newNodes.getLength(),kind);for(int i=0;i<oldNodes.getLength();i++)assertTrue(oldNodes.item(i).isEqualNode(newNodes.item(i)),"Preserved native "+kind+" "+i);}
        NodeList texts=newXml.getElementsByTagNameNS(W,"t");for(int i=0;i<texts.getLength();i++){String text=texts.item(i).getTextContent();assertFalse(text.contains("\n")||text.contains("\t"),"User controls use native breaks/tabs, not XML text controls");}
    }
    @Test void illegalControlsAndUnpairedSurrogatesCannotReplaceGuardedBodyText()throws Exception {
        String scenario="unsupported-characters",id=project();mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0));
        Map<String,Object> before=document(id),block=paragraph(before,546);byte[] oldWord=word(id);json(scenario,"document-before.json",before);evidence(scenario,"NTT-before.docx",oldWord);
        String[] invalid={"\u0000","\u000b","\u001f","\ufffe","\uffff","\ud800","\udc00","\ud800\n\udc00","\ud800\t\udc00","\r"};
        for(int i=0;i<invalid.length;i++) {
            Map<String,Object> request=DraftBusinessRules.map("revisionId",before.get("revisionId"),"docxSha256",before.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",block.get("id"),"bindingId",block.get("bindingId"),"expectedTextHash",block.get("textHash"),"text",block.get("text")+invalid[i]+"TEST ONLY must reject.")));
            String raw=JsonUtils.write(request).replace("\ud800","\\ud800").replace("\udc00","\\udc00");evidence(scenario,"save-request-"+i+".json",raw.getBytes(StandardCharsets.UTF_8));
            String response=mvc.perform(put("/api/drafting/{id}/documents/NTT",id).contentType(MediaType.APPLICATION_JSON).content(raw)).andReturn().getResponse().getContentAsString();Map<String,Object> result=JsonUtils.readMap(response);json(scenario,"save-response-"+i+".json",result);
            byte[] current=word(id);evidence(scenario,"NTT-current-"+i+".docx",current);json(scenario,"document-current-"+i+".json",document(id));
            assertEquals(4012,result.get("code"),String.valueOf(result.get("message")));assertTrue(String.valueOf(result.get("message")).contains("UNSUPPORTED_REPLACEMENT_CHARACTER"),String.valueOf(result.get("message")));assertArrayEquals(oldWord,current,"Rejected input leaves the actual saved package intact");
        }
        Map<String,Object> after=document(id);json(scenario,"document-after.json",after);evidence(scenario,"NTT-after.docx",word(id));assertEquals(before.get("revisionId"),after.get("revisionId"));assertEquals(before.get("docxSha256"),after.get("docxSha256"));assertEquals(block.get("text"),paragraph(after,546).get("text"));
    }
    @SuppressWarnings("unchecked")
    @Test void newlineDoesNotMakeProtectedFieldParagraphsEditable()throws Exception {
        String scenario="protected-field",id=project();mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0));
        Map<String,Object> before=document(id,2),block=((List<Map<String,Object>>)before.get("blocks")).stream().filter(b->"FIELD_TEXT_EDIT_UNSUPPORTED".equals(b.get("unsupportedReason"))).findFirst().get();assertEquals(false,block.get("editable"));byte[] oldWord=word(id,"SCC");
        Map<String,Object> request=DraftBusinessRules.map("revisionId",before.get("revisionId"),"docxSha256",before.get("docxSha256"),"blocks",Collections.singletonList(DraftBusinessRules.map("id",block.get("id"),"bindingId",block.get("bindingId"),"expectedTextHash",block.get("textHash"),"text",block.get("text")+"\nTEST ONLY must reject.")));
        json(scenario,"document-before.json",before);json(scenario,"save-request.json",request);evidence(scenario,"SCC-before.docx",oldWord);
        String response=mvc.perform(put("/api/drafting/{id}/documents/SCC",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(request))).andReturn().getResponse().getContentAsString();Map<String,Object> result=JsonUtils.readMap(response);json(scenario,"save-response.json",result);
        assertEquals(4012,result.get("code"),response);assertTrue(String.valueOf(result.get("message")).contains("FIELD_TEXT_EDIT_UNSUPPORTED"),response);Map<String,Object> after=document(id,2);json(scenario,"document-after.json",after);evidence(scenario,"SCC-after.docx",word(id,"SCC"));assertEquals(before.get("revisionId"),after.get("revisionId"));assertArrayEquals(oldWord,word(id,"SCC"));
    }
    private static Map<String,byte[]> parts(byte[] bytes)throws IOException {Map<String,byte[]> out=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){ByteArrayOutputStream value=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=zip.read(buffer))!=-1)value.write(buffer,0,n);out.put(entry.getName(),value.toByteArray());}}return out;}
    private static Document xml(byte[] bytes)throws Exception {DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));}
}
