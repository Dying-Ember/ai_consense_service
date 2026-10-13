package com.consense.service.drafting;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DraftSourceBillIdentityTest {
    private static Map<String,Object> row(String number,String type,String description){return DraftBusinessRules.map("number",number,"type",type,"description",description);}
    private static Map<String,Object> plan(String mode){return DraftBusinessRules.plan(DraftBusinessRules.map("electronicTendering",mode,"billNos",Arrays.asList(row("1","BQ","Preliminaries"),row("2","BQ","Preambles"),row("7","BQ","Drainage revised scope"),row("9","SOR","Electrical Works"),row("13","BQ","Provisional Sums"))));}
    private static Map<String,Object> action(Map<String,Object> plan,String id){return DraftBusinessRules.list(plan.get("actions")).stream().map(DraftBusinessRules::asMap).filter(v->id.equals(v.get("id"))).findFirst().get();}
    private static String source(){StringJoiner text=new StringJoiner("\n");for(int n=1;n<=1238;n++)text.add(DraftBusinessRules.standardParagraph("SCT",n));return text.toString();}
    private static String onlyIdentity(String source,Map<String,Object> plan){Map<String,Object> one=new LinkedHashMap<>(plan);one.put("actions",Arrays.asList(action(plan,"sct-issue-alternative"),action(plan,"sct-source-bill-identities")));return DraftClauseRules.apply(source,"SCT",one);}
    @Test void knownDescriptionCanBeWrittenWhileUnmatchedRowsAndDistributionStayUnresolved(){
        Map<String,Object> plan=plan("L10Pro");String actual=onlyIdentity(source(),plan);
        assertTrue(actual.contains("Bill No. 7 – Drainage revised scope"));assertFalse(actual.contains("Bill No. 9 – Electrical Works"));assertFalse(actual.contains("Bill No. 13 – Provisional Sums"));
        assertEquals("pending",action(plan,"sct-project-bq-sor-placement").get("action"));
        assertEquals(2,DraftBusinessRules.list(DraftBusinessRules.asMap(plan.get("derived")).get("billIdentityRowsWithoutSourcePlacement")).size());
    }
    @Test void hardcopyUsesOnlyTheChosenBranchAndChangedIdentityAnchorIsRejected(){
        Map<String,Object> plan=plan("Hardcopy");String actual=onlyIdentity(source(),plan);
        assertTrue(actual.contains("Bill No. 7 – Drainage revised scope"));assertEquals("applied",action(plan,"sct-source-bill-identities").get("applicationState"));
        Map<String,Object> changed=plan("L10Pro");String original=source().replace("Bill No. 7 –","Bill No. 77 –");
        String rejected=onlyIdentity(original,changed);assertTrue(rejected.contains("Bill No. 77 –"));assertFalse(rejected.contains("Drainage revised scope"));assertEquals("unresolved",action(changed,"sct-source-bill-identities").get("applicationState"));
    }
    @Test void missingTypeNeverSilentlyUsesAQuotedBillNumberAsAPricingType(){
        Map<String,Object> plan=DraftBusinessRules.plan(DraftBusinessRules.map("electronicTendering","L10Pro","billNos",Arrays.asList(DraftBusinessRules.map("number","7","description","Drainage revised scope"))));
        assertTrue(DraftBusinessRules.list(action(plan,"sct-source-bill-identities").get("value")).isEmpty());
        assertEquals(1,DraftBusinessRules.list(DraftBusinessRules.asMap(plan.get("derived")).get("billIdentityRowsWithoutSourcePlacement")).size());
    }
}
