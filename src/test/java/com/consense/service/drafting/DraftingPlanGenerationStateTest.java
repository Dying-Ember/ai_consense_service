package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.*;
import com.consense.ocr.OcrClient;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.web.dto.DraftingDtos.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Actual service plan/generate seam with repository doubles; no business DB or model call. */
class DraftingPlanGenerationStateTest {
    static final String PROJECT="isolated-plan-generation-state";
    static class Fixture {
        final Map<String,DraftVariable> variables=new LinkedHashMap<>();
        final Map<String,SourceDocument> templates=new LinkedHashMap<>();
        final List<SourceDocument> correspondence=new ArrayList<>();
        final Map<String,byte[]> files=new LinkedHashMap<>();
        final Map<String,DraftDocument> documents=new LinkedHashMap<>();
        final Map<String,DraftArtifact> artifacts=new LinkedHashMap<>();
        final DraftDocumentRepository docs=mock(DraftDocumentRepository.class);
        final DraftArtifactRepository revisions=mock(DraftArtifactRepository.class);
        final DraftVariableRepository inputs=mock(DraftVariableRepository.class);
        final SourceDocumentRepository sources=mock(SourceDocumentRepository.class);
        final AiGateway model=mock(AiGateway.class);
        final ConsenseProperties props=new ConsenseProperties();
        final DocumentParser parser=new DocumentParser(props,mock(OcrClient.class));
        final StorageService storage=mock(StorageService.class);
        final DraftingService service;
        Fixture() {
            ProjectService projects=mock(ProjectService.class);Project p=new Project();p.setId(PROJECT);when(projects.require(PROJECT)).thenReturn(p);
            when(inputs.findByProjectIdAndVarKey(eq(PROJECT),anyString())).thenAnswer(i->Optional.ofNullable(variables.get(i.getArgument(1))));
            when(inputs.findByProjectIdOrderBySortOrderAsc(PROJECT)).thenAnswer(i->new ArrayList<>(variables.values()));
            when(inputs.save(any(DraftVariable.class))).thenAnswer(i->{DraftVariable v=i.getArgument(0);variables.put(v.getVarKey(),v);return v;});
            when(sources.findFirstByProjectIdAndFileKeyAndCategory(eq(PROJECT),anyString(),eq(SourceDocument.CATEGORY_STANDARD_TEMPLATE))).thenAnswer(i->Optional.ofNullable(templates.get(i.getArgument(1))));
            when(sources.findByProjectIdAndCategoryOrderByIdAsc(eq(PROJECT),anyString())).thenAnswer(i->SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(i.getArgument(1))?new ArrayList<>(templates.values()):new ArrayList<>(correspondence));
            when(storage.read(anyString())).thenAnswer(i->files.get(i.getArgument(0)).clone());
            when(docs.findByProjectIdAndFileKey(eq(PROJECT),anyString())).thenAnswer(i->Optional.ofNullable(documents.get(i.getArgument(1))));
            when(docs.findByProjectIdOrderByIdAsc(PROJECT)).thenAnswer(i->new ArrayList<>(documents.values()));
            when(docs.save(any(DraftDocument.class))).thenAnswer(i->{DraftDocument d=i.getArgument(0);documents.put(d.getFileKey(),d);return d;});
            when(revisions.findById(anyString())).thenAnswer(i->Optional.ofNullable(artifacts.get(i.getArgument(0))));
            when(revisions.save(any(DraftArtifact.class))).thenAnswer(i->{DraftArtifact a=i.getArgument(0);artifacts.put(a.getId(),a);return a;});
            service=new DraftingService(projects,storage,parser,model,sources,inputs,docs,revisions,mock(DraftPdfArtifactRepository.class),props,null,null,null,null,null,null);
        }
        void value(String key,Object value) {DraftVariable v=new DraftVariable();v.setProjectId(PROJECT);v.setScope("INPUT");v.setVarKey(key);v.setConfirmed(true);v.setValueText(value instanceof String?(String)value:JsonUtils.write(value));variables.put(key,v);}
        void source(String key,byte[] bytes) {
            DocumentParser.ParsedDocument parsed=parser.parse(key+".docx",bytes);SourceDocument s=new SourceDocument();s.setId((long)templates.size()+1);s.setProjectId(PROJECT);s.setFileKey(key);s.setFileName(key+".docx");s.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);s.setStoragePath(key);s.setParseStatus("PARSED");s.setTextContent(parsed.getText());s.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));templates.put(key,s);files.put(key,bytes.clone());
        }
        Map<String,Object> seedFrozen()throws Exception {
            byte[] bytes;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){word.createParagraph().createRun().setText("Untouched source condition.");word.write(out);bytes=out.toByteArray();}
            for(String key:DraftBlueprint.draftFileKeys())source(key,bytes);
            value("foundationIncluded",false);service.listVariables(PROJECT);
            Map<String,Object> frozen=service.plan(PROJECT,null);
            Map<String,Object> target=action(frozen,"scc-excision");target.put("action","not_adopted");target.put("decisionAction","not_adopted");target.put("application","applied");target.put("applicationState","applied");
            String plan=JsonUtils.write(frozen);
            for(String key:DraftBlueprint.draftFileKeys()) {
                DraftDocument d=new DraftDocument();d.setProjectId(PROJECT);d.setFileKey(key);d.setGenerated(true);d.setContentEdited(false);d.setSnapshotId((String)frozen.get("snapshotId"));d.setRuleVersion((String)frozen.get("ruleVersion"));d.setRevisionId("fixture-"+key);d.setInputSnapshotJson(plan);documents.put(key,d);
                DraftArtifact a=new DraftArtifact();a.setId(d.getRevisionId());a.setProjectId(PROJECT);a.setFileKey(key);a.setSnapshotId(d.getSnapshotId());a.setSourceSha256(DraftPdfConverter.sha256(bytes));a.setDocxBytes(bytes);a.setDocxSha256(DraftPdfConverter.sha256(bytes));artifacts.put(a.getId(),a);
            }
            return frozen;
        }
    }
    static Map<String,Object> action(Map<String,Object> p,String id) {return DraftBusinessRules.list(p.get("actions")).stream().map(DraftBusinessRules::asMap).filter(a->id.equals(a.get("id"))).findFirst().orElseThrow(AssertionError::new);}
    static Map<String,Long> states(Map<String,Object> p,String key) {Map<String,Long> r=new LinkedHashMap<>();for(Object raw:DraftBusinessRules.list(p.get("actions"))){Map<String,Object>a=DraftBusinessRules.asMap(raw);if(key.equals(a.get("document")))r.merge(String.valueOf(a.get("applicationState")),1L,Long::sum);}return r;}
    static void fresh(Fixture f) {assertNotEquals("applied",action(f.service.plan(PROJECT,null),"scc-excision").get("applicationState"));verifyNoInteractions(f.model);}

    @Test void actualThreeOriginalGenerationAndImmediatePlanHaveTheSameSourceApplicationStates()throws Exception {
        String dir=System.getProperty("consense.audit.planCaptureDir");assumeTrue(dir!=null,"Explicit preserved real-original capture required.");Path capture=Paths.get(dir);
        Fixture f=new Fixture();Map<String,Object> values=JsonUtils.readMap(new String(Files.readAllBytes(capture.resolve("confirmed-values.json")),"UTF-8"));values.forEach(f::value);
        for(String key:DraftBlueprint.draftFileKeys())f.source(key,Files.readAllBytes(capture.resolve(key+"-source.docx")));
        f.service.listVariables(PROJECT);Map<String,Object> before=f.service.plan(PROJECT,null);
        String inputsBefore=JsonUtils.write(f.variables.values());List<DraftDocumentVO> generated=f.service.generate(PROJECT,"en");assertEquals(3,generated.size());
        Map<String,Object> saved=JsonUtils.readMap(f.documents.get("SCC").getInputSnapshotJson());Map<String,Object> read=f.service.plan(PROJECT,null);
        Map<String,Object> report=DraftBusinessRules.map("savedStates",states(saved,"SCC"),"planStates",states(read,"SCC"),"beforeStates",states(before,"SCC"),"applicationStateSource",read.get("applicationStateSource"),"revisions",new ArrayList<>(f.artifacts.keySet()),"newModelCalls",0,"businessDatabaseWrites",0);
        String out=System.getProperty("consense.audit.planResult");if(out!=null)Files.write(Paths.get(out),JsonUtils.write(report).getBytes("UTF-8"));
        assertEquals(inputsBefore,JsonUtils.write(f.variables.values()));verifyNoInteractions(f.model);
        assertEquals(states(saved,"SCC"),states(read,"SCC"),"Plan must represent the current saved source compile, not a lossy flat-text reevaluation.");
        for(String key:DraftBlueprint.draftFileKeys())assertEquals(states(saved,key),states(read,key));
        assertEquals(DraftBusinessRules.list(saved.get("unresolved")),DraftBusinessRules.list(read.get("unresolved")),"Independent pending issues must survive.");
    }
    @Test void matchingCurrentRevisionsExposeTheirFrozenApplicationState()throws Exception {Fixture f=new Fixture();f.seedFrozen();assertEquals("applied",action(f.service.plan(PROJECT,null),"scc-excision").get("applicationState"));verifyNoInteractions(f.model);}
    @Test void changedAdoptedInputsRejectTheOldGenerationState()throws Exception {Fixture f=new Fixture();f.seedFrozen();f.value("foundationIncluded",true);fresh(f);}
    @Test void changedCorrespondenceCannotBorrowTheOldSourceCompile()throws Exception {Fixture f=new Fixture();f.seedFrozen();SourceDocument evidence=new SourceDocument();evidence.setId(91L);evidence.setProjectId(PROJECT);evidence.setCategory(SourceDocument.CATEGORY_PROJECT_INPUT);evidence.setTextContent("Changed project correspondence.");f.correspondence.add(evidence);fresh(f);assertEquals(true,f.service.plan(PROJECT,null).get("evidenceChangedSinceExtraction"));}
    @Test void previewPatchesNeverExposeAnOldGenerationAsTheirResult()throws Exception {Fixture f=new Fixture();f.seedFrozen();Map<String,Object> p=f.service.plan(PROJECT,Collections.singletonMap("foundationIncluded","true"));assertNotEquals("applied",action(p,"scc-excision").get("applicationState"));assertEquals(true,p.get("preview"));verifyNoInteractions(f.model);}
    @Test void changedTemplateMetadataOrOriginalBytesRejectTheOldGeneration()throws Exception {Fixture f=new Fixture();f.seedFrozen();f.templates.get("SCC").setTextContent("Changed source condition.");fresh(f);f=new Fixture();f.seedFrozen();byte[] altered=f.files.get("SCC").clone();altered[altered.length-1]^=1;f.files.put("SCC",altered);fresh(f);}
    @Test void missingOrBodyEditedDraftsCannotBorrowCompiledState()throws Exception {Fixture f=new Fixture();f.seedFrozen();f.documents.remove("NTT");fresh(f);f=new Fixture();f.seedFrozen();f.documents.get("SCT").setContentEdited(true);fresh(f);}
    @Test void mismatchingRuleVersionOrSiblingFrozenPlanCannotBorrowState()throws Exception {Fixture f=new Fixture();f.seedFrozen();f.documents.get("SCC").setRuleVersion("different-rule");fresh(f);f=new Fixture();f.seedFrozen();Map<String,Object> p=JsonUtils.readMap(f.documents.get("SCT").getInputSnapshotJson());p.put("evidenceRevision","stale-evidence");f.documents.get("SCT").setInputSnapshotJson(JsonUtils.write(p));fresh(f);}
    @Test void artifactSourceRevisionAndByteIntegrityMustAllMatch()throws Exception {for(String change:Arrays.asList("source","snapshot","file","bytes","parent","missing")){Fixture f=new Fixture();f.seedFrozen();DraftArtifact a=f.artifacts.get("fixture-SCC");switch(change){case "source":a.setSourceSha256("wrong");break;case "snapshot":a.setSnapshotId("wrong");break;case "file":a.setFileKey("NTT");break;case "bytes":a.setDocxSha256("wrong");break;case "parent":a.setParentRevisionId("manual-parent");break;default:f.artifacts.remove(a.getId());}fresh(f);}}
    @Test void regenerationMustCompileFreshInsteadOfTrustingPreviousSuccess()throws Exception {Fixture f=new Fixture();f.seedFrozen();f.service.generate(PROJECT,"en");Map<String,Object> saved=JsonUtils.readMap(f.documents.get("SCC").getInputSnapshotJson());assertNotEquals("applied",action(saved,"scc-excision").get("applicationState"));assertFalse(f.artifacts.containsKey(f.documents.get("SCC").getRevisionId())&&f.documents.get("SCC").getRevisionId().startsWith("fixture-"));verifyNoInteractions(f.model);}
}
