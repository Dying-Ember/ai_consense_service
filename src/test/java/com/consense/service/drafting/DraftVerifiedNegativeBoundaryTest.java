package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual-source fail-closed controls, separate from any recognition/model decision. */
class DraftVerifiedNegativeBoundaryTest {
    static byte[] bytes;static TemplateIndex source;static Map<String,Object> values;
    @BeforeAll static void load()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("consense.audit.captureDir")!=null&&!System.getProperty("consense.audit.captureDir").isBlank(),"Actual-source replay requires explicit -Dconsense.audit.captureDir; no original or user answers are committed.");
        Path capture=Paths.get(System.getProperty("consense.audit.captureDir"));bytes=Files.readAllBytes(capture.resolve("SCC-source.docx"));source=new DocxTemplateEditor().inspect(bytes);
        values=DraftBusinessRules.asMap(DraftBusinessRules.asMap(JsonUtils.readMap(Files.readString(capture.resolve("plan-api.json"))).get("data")).get("inputValues"));
    }
    static Map<String,Object> plan(){return DraftBusinessRules.plan(values);}
    static Map<String,Object> action(Map<String,Object> plan,String id){return DraftBusinessRules.list(plan.get("actions")).stream().map(DraftBusinessRules::asMap).filter(a->id.equals(a.get("id"))).findFirst().orElseThrow();}
    static Map<String,Object> only(Map<String,Object> plan,Map<String,Object> action){plan.put("actions",new ArrayList<>(Collections.singletonList(action)));return plan;}
    static void rejected(String text,TemplateIndex index,Map<String,Object> plan,Map<String,Object> action){
        DraftSourceEditCompiler compiler=new DraftSourceEditCompiler(index,"SCC");String actual=DraftClauseRules.apply(text,"SCC",only(plan,action),compiler);
        assertEquals(text,actual);assertEquals("unresolved",action.get("applicationState"));assertTrue(compiler.edits().isEmpty());
    }
    @Test void changedBodyAndInsertedProjectTextKeepTheWholeOriginal(){
        for(String text:Arrays.asList(new DraftSourceEditCompiler(source,"SCC").projection().replace(DraftBusinessRules.standardParagraph("SCC",954),"Additional project-specific excision obligations."),new DraftSourceEditCompiler(source,"SCC").projection().replace(DraftBusinessRules.standardParagraph("SCC",955),DraftBusinessRules.standardParagraph("SCC",955)+"\nAdditional project text."))){Map<String,Object> p=plan();rejected(text,source,p,action(p,"scc-excision"));}
    }
    @Test void changedFollowingChapterCannotBeSwallowedByAWholeClause(){
        Map<String,Object> p=plan();String text=new DraftSourceEditCompiler(source,"SCC").projection().replace(DraftBusinessRules.standardParagraph("SCC",957),"SCC10CHANGED FOLLOWING CHAPTER");rejected(text,source,p,action(p,"scc-excision"));
    }
    @Test void unknownActionAndWrongClauseIdentityStayUnresolved(){
        for(boolean wrongClause:Arrays.asList(false,true)){Map<String,Object> p=plan(),a=action(p,"scc-excision");if(wrongClause)a.put("clause","SCC8.304");else a.put("id","unreviewed-excision-output");rejected(new DraftSourceEditCompiler(source,"SCC").projection(),source,p,a);}
    }
    @Test void changedTocTitleOrCachedPageCannotBeSilentlyAccepted(){
        for(String changed:Arrays.asList("SCC9.301Changed TOC title34","SCC9.301Works Subject to Excision35")){Map<String,Object> p=plan();String text=new DraftSourceEditCompiler(source,"SCC").projection().replace(DraftBusinessRules.standardParagraph("SCC",54),changed);rejected(text,source,p,action(p,"scc-excision"));}
    }
    @Test void sameTextInARepackedDifferentSourceEditionIsRejected()throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes));ZipOutputStream out=new ZipOutputStream(output)){ZipEntry entry;while((entry=in.getNextEntry())!=null){ZipEntry copy=new ZipEntry(entry.getName());copy.setTime(0);out.putNextEntry(copy);in.transferTo(out);out.closeEntry();}}
        TemplateIndex repacked=new DocxTemplateEditor().inspect(output.toByteArray());assertNotEquals(source.getSourceSha256(),repacked.getSourceSha256());Map<String,Object> p=plan();rejected(new DraftSourceEditCompiler(repacked,"SCC").projection(),repacked,p,action(p,"scc-excision"));
    }
    @Test void positiveAndManualRetainAreNotPromotedToAutomaticNegative(){
        for(boolean manual:Arrays.asList(false,true)){Map<String,Object> p=plan(),a=action(p,"scc-excision");a.put("action","retain");a.remove("decisionAction");if(manual)a.put("manual",true);DraftSourceEditCompiler c=new DraftSourceEditCompiler(source,"SCC");String before=c.projection();assertEquals(before,DraftClauseRules.apply(before,"SCC",only(p,a),c));assertEquals("retained",a.get("applicationState"));assertTrue(c.edits().isEmpty());}
    }
    @Test void foundationCoverageRequiresTheActuallyAppliedSctDeletion(){
        for(String state:Arrays.asList("pending","unresolved","retained","applied")){
            Map<String,Object> p=plan(),reference=action(p,"scc-foundation-version-reference"),parent=action(p,"sct-foundation-assessment");parent.put("applicationState",state);p.put("actions",new ArrayList<>(Arrays.asList(parent,reference)));
            String before=new DraftSourceEditCompiler(source,"SCC").projection();DraftSourceEditCompiler c=new DraftSourceEditCompiler(source,"SCC");assertEquals(before,DraftClauseRules.apply(before,"SCC",p,c));assertEquals("applied".equals(state)?"retained":"unresolved",reference.get("applicationState"));assertTrue(c.edits().isEmpty());
        }
        Map<String,Object> p=plan(),reference=action(p,"scc-foundation-version-reference");rejected(new DraftSourceEditCompiler(source,"SCC").projection(),source,p,reference);
    }
    @Test void aNegativeChildCannotBeCoveredBeforeItsParentHasActuallyApplied(){
        for(String state:Arrays.asList("pending","unresolved","retained","missing")){
            Map<String,Object> p=plan(),parent=action(p,"scc-advance-adoption"),child=action(p,"scc-advance-repayment-standard");if(!"missing".equals(state))parent.put("applicationState",state);
            p.put("actions",new ArrayList<>(Arrays.asList(child,parent)));DraftClauseRules.apply(new DraftSourceEditCompiler(source,"SCC").projection(),"SCC",p);
            assertEquals("unresolved",child.get("applicationState"),state);assertEquals("applied",parent.get("applicationState"));
        }
    }
    @Test void adjacentNegativeScopesRetainAlreadyAppliedFollowingClauseBoundaries(){
        Map<String,Object> p=plan();Set<String> parents=new LinkedHashSet<>();DraftCurrentNegativeOutputReplayTest.ordinaryParents().forEach(parents::add);
        p.put("actions",DraftBusinessRules.list(p.get("actions")).stream().filter(a->parents.contains(DraftBusinessRules.asMap(a).get("id"))).collect(java.util.stream.Collectors.toList()));
        String actual=DraftClauseRules.apply(new DraftSourceEditCompiler(source,"SCC").projection(),"SCC",p);
        for(Object raw:DraftBusinessRules.list(p.get("actions"))){Map<String,Object> a=DraftBusinessRules.asMap(raw);assertEquals("applied",a.get("applicationState"),String.valueOf(a));DraftClauseRules.NegativeScope scope=DraftClauseRules.negativeScope(a);assertTrue(actual.contains(scope.clause+" Not used"));assertTrue(String.valueOf(a.get("appliedAnchor")).contains("TOC P"+scope.toc));}
        assertTrue(actual.contains(DraftBusinessRules.standardParagraph("SCC",949)));assertTrue(actual.contains(DraftBusinessRules.standardParagraph("SCC",957)));assertTrue(actual.contains(DraftBusinessRules.standardParagraph("SCC",1224)));
    }
}
