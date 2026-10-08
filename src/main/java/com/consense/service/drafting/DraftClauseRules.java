package com.consense.service.drafting;

import com.consense.common.BizException;
import java.util.*;
import java.util.regex.*;

/** Narrow template edits and protection for the two expressly specified NTT clauses. */
public final class DraftClauseRules {
    private DraftClauseRules() { }
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s*(?:#{1,6}\\s*)?\\*?(\\d{1,3})\\.(?:[ \\t]|$)");

    /** Apply source-bound deterministic edits. Unmatched/unsupported targets keep their original text. */
    public static String apply(String source,String document,Map<String,Object> plan) {
        return apply(source,document,plan,null);
    }
    interface SourceListener { void adopted(Map<String,Object> action,List<SourceChange> changes,boolean changed); }
    static final class SourceChange {
        final int paragraph,scopeEnd;final int[] rowParagraphs;final String replacement,clause;final boolean insertion,after;
        SourceChange(int p,String text,boolean insertion,boolean after){paragraph=p;replacement=text;this.insertion=insertion;this.after=after;clause=null;scopeEnd=0;rowParagraphs=null;}
        SourceChange(String clause,String text){paragraph=0;replacement=text;insertion=false;after=false;this.clause=clause;scopeEnd=0;rowParagraphs=null;}
        SourceChange(int first,int last){paragraph=first;scopeEnd=last;rowParagraphs=null;replacement="";clause=null;insertion=false;after=false;}
        SourceChange(int[] rows){paragraph=0;scopeEnd=0;rowParagraphs=rows.clone();replacement="";clause=null;insertion=false;after=false;}
    }
    static String apply(String source,String document,Map<String,Object> plan,SourceListener listener) {
        String text=source==null?"":source;
        List<Object> actions=DraftBusinessRules.list(plan.get("actions"));
        Set<String> manualParents=new HashSet<>();Set<Integer> manualParagraphs=new HashSet<>();Map<Integer,String> paragraphs=new HashMap<>();
        for(Object o:actions){Map<String,Object>a=DraftBusinessRules.asMap(o);if(document.equals(a.get("document"))&&Boolean.TRUE.equals(a.get("manual"))){manualParents.add(String.valueOf(a.get("clause")));manualParagraphs.addAll(targetParagraphs(a));}}
        for(Object o:actions){Map<String,Object>a=DraftBusinessRules.asMap(o);if(!document.equals(a.get("document")))continue;
            String id=String.valueOf(a.get("id"));String decision=String.valueOf(a.getOrDefault("decisionAction",a.get("action")));a.putIfAbsent("decisionAction",decision);
            if(!a.containsKey("sourceText")){String actual=sourceForTarget(source,document,a);if(!actual.isEmpty())a.put("sourceText",actual);}
            if("pending".equals(decision)){a.put("application","pending");a.put("applicationState","pending");continue;}
            if(!Boolean.TRUE.equals(a.get("manual"))&&(hasManualParent(String.valueOf(a.get("clause")),manualParents)||!Collections.disjoint(targetParagraphs(a),manualParagraphs))){a.put("application","covered_by_manual_parent");a.put("applicationState","covered_by_manual_parent");continue;}
            Editor e=new Editor(text,document,a,plan,paragraphs);
            try {
                if(Boolean.TRUE.equals(a.get("manual")))applyManual(e,decision);
                else applyKnown(e,id,decision);
                if(e.reason==null&&listener!=null)listener.adopted(a,e.changes,e.changed);
                if(e.reason!=null){unresolved(plan,a,e.reason);a.put("action","pending");a.put("application","unresolved");a.put("applicationState","unresolved");}
                else {text=e.text;paragraphs=e.current;a.put("action",decision);a.put("application",e.changed?"applied":"retained");a.put("applicationState",e.changed?"applied":"retained");a.put("appliedAnchor",a.get("paragraphs"));removeApplicationIssue(plan,id);}
            }catch(RuntimeException ex){unresolved(plan,a,"Source target could not be applied safely: "+ex.getMessage());a.put("action","pending");a.put("application","unresolved");a.put("applicationState","unresolved");a.put("formatError",ex.getMessage());}
        }
        return text;
    }
    private static boolean hasManualParent(String clause,Set<String> parents){for(String parent:parents)if(!parent.equals(clause)&&clause.startsWith(parent+"("))return true;return false;}
    private static String sourceForTarget(String source,String doc,Map<String,Object>a){Editor e=new Editor(source==null?"":source,doc,a,Collections.emptyMap(),Collections.emptyMap());StringJoiner found=new StringJoiner("\n");for(int n:targetParagraphs(a)){String standard=e.standard(n);if(standard.trim().isEmpty()||e.isPageFurniture(standard))continue;Matcher m=e.exact(standard).matcher(e.text);if(m.find()){String actual=m.group();if(!m.find())found.add(actual);}}if(found.length()>0)return found.toString();Matcher root=Pattern.compile("(?:NTT\\d+|SCT\\d+|SCC\\d+\\.\\d{3})").matcher(String.valueOf(a.get("clause")).replace(" ",""));if(root.find()){int[]range=sectionRange(e.text,root.group());if(range!=null)return e.text.substring(range[0],range[1]);}return "";}
    private static Set<Integer> targetParagraphs(Map<String,Object>a){Set<Integer> out=new LinkedHashSet<>();String id=String.valueOf(a.get("id"));int[] special=DraftSourceAnchors.substantive(id);if(special!=null){for(int p:special)out.add(p);return out;}Matcher m=Pattern.compile("P(\\d+)(?:[–-]P?(\\d+))?").matcher(String.valueOf(a.get("paragraphs")));while(m.find()){int start=Integer.parseInt(m.group(1)),end=m.group(2)==null?start:Integer.parseInt(m.group(2));for(int n=start;n<=end;n++)out.add(n);}return out;}
    private static void applyManual(Editor e,String decision){
        Object value=e.action.get("value");String id=String.valueOf(e.action.get("id"));
        if("retain".equals(decision)){e.check();return;}
        if("amend".equals(decision)){
            if(value==null){e.fail("An exact adopted target amendment is required.");return;}
            String replacement=String.valueOf(value);
            if("NTT-13-SUBCONTRACT".equals(id)){e.p(e.anchor(0),replacement);e.pOptional(e.guidance(0),"");return;}
            if("NTT-10-BOND-REFERENCE".equals(id)){e.p(e.anchor(0),replacement);return;}
            if("sct-tender-system".equals(id)){e.whole("SCT5",replacement);return;}
            if("scc-technical-proposal-reference".equals(id)){e.p(e.anchor(0),replacement);return;}
            if("scc-ovt-protection".equals(id)){e.whole("SCC22.301",replacement);return;}
            if(id.contains("REFERENCE")||id.endsWith("reference-check")||id.contains("crosschecks")||id.contains("version-reference")){
                // A source mapping may certify retention; replacement text must identify its exact target.
                if(id.equals("NTT-10-SCC-REFERENCE")){e.p(e.anchor(0),replacement);return;}
                if(id.equals("NTT-13-SCC-REFERENCE")){e.p(e.anchor(0),replacement);return;}
                if(id.equals("NTT-16-HOMES-REFERENCE")){e.p(e.anchor(0),replacement);return;}
                e.fail("Supply a project-source target mapping for this multi-position or external check; a blanket replacement is unsafe.");return;
            }
            String clause=String.valueOf(e.action.get("clause")).replace(" ","");
            if(clause.matches("(?:SCT\\d+|SCC\\d+\\.\\d{3})")){e.whole(clause,replacement);return;}
            int[] target=manualParagraphs(id);if(target!=null){e.p(target[0],replacement);for(int i=1;i<target.length;i++)e.pOptional(target[i],"");return;}
            e.fail("This target requires an exact source mapping; no safe replacement scope has been established.");return;
        }
        if("not_used".equals(decision)||"delete".equals(decision)||"not_adopted".equals(decision)){
            if("NTT-9-WTO".equals(id)&&!"not_adopted".equals(decision)){e.whole("NTT9","not_used".equals(decision)?"9. Not used":"");return;}
            int[] target=manualParagraphs(id);if(target!=null){e.p(target[0],"not_used".equals(decision)?subitemPrefix(e.standard(target[0]))+"Not used":"");for(int i=1;i<target.length;i++)e.pOptional(target[i],"");return;}
            String clause=String.valueOf(e.action.get("clause")).replace(" ","");
            if(clause.matches("(?:SCT\\d+|SCC\\d+\\.\\d{3}|NTT\\d+)")){e.whole(clause,"not_used".equals(decision)?clause+" Not used":"");return;}
            e.fail("The adopted negative action has no verified source scope; preserve the original pending target mapping.");
        }
    }
    private static int[] manualParagraphs(String id){DraftSourceAnchors.Target target=DraftSourceAnchors.target(id);return target==null?null:target.manualParagraphs();}
    private static String subitemPrefix(String source){Matcher m=Pattern.compile("^\\*?(\\([a-z0-9]+\\))").matcher(source);return m.find()?m.group(1)+" ":"";}
    private static void applyKnown(Editor e,String id,String decision){Map<String,Object>v=DraftBusinessRules.asMap(e.plan.get("effectiveValues"));Object value=e.action.get("value");
        switch(id){
        case"NTT-1E-FACADE":if("delete".equals(decision))e.p(e.anchor(0),"");else e.checkParagraph(e.anchor(0));e.pOptional(e.guidance(0),"");return;
        case"NTT-2-L10PRO":if("delete".equals(decision))e.removeRange(97,124);else{e.checkParagraph(104);e.removeRangeBetween(107,109,106,111);e.removeRangeBetween(122,124,120,127);}return;
        case"NTT-2-PAPER":if("delete".equals(decision))e.removeRange(127,135);else{e.checkParagraph(127);e.pOptional(135,"");}return;
        case"NTT-3C-L10PRO":if("delete".equals(decision))e.p(e.anchor(0),"");else e.checkParagraph(e.anchor(0));e.removeRange(e.guidance(0),e.guidance(1));return;
        case"NTT-3C-PAPER":if("delete".equals(decision))e.p(e.anchor(0),"");else e.checkParagraph(e.anchor(0));e.pOptional(e.guidance(0),"");return;
        case"NTT-10-BOND-REFERENCE":{String src=e.standard(e.anchor(0));if(!src.matches("(?s).*\\*?G1\\s*/\\s*\\*?G1a\\b.*")){e.fail("The standard source has no G1/G1a alternative.");return;}e.p(e.anchor(0),src.replaceAll("\\*?G1\\s*/\\s*\\*?G1a\\b",String.valueOf(value)));e.pOptional(e.guidance(0),"");return;}
        case"NTT-13-SUBCONTRACT":if("amend".equals(decision)){e.p(e.anchor(0),String.valueOf(value));e.pOptional(e.guidance(0),"");}else{e.checkParagraph(e.anchor(0));e.pOptional(e.guidance(0),"");}return;
        case"NTT-5F-EXCISION":if("delete".equals(decision))e.p(e.anchor(0),"");else e.checkParagraph(e.anchor(0));e.pOptional(e.guidance(0),"");return;
        case"NTT-9-WTO":if("not_used".equals(decision)){e.whole("NTT9","9. Not used");}else{e.checkParagraph(438);e.pOptional(439,"");}return;
        case"NTT-17-RAILWAY":if("not_used".equals(decision))e.whole("NTT17","17. Not used");else{e.checkParagraph(619);for(int p:new int[]{614,617,620})e.pOptional(p,"");}return;
        case"NTT-3AB-PROCEDURE":e.checkParagraph(e.anchor(0));e.checkParagraph(e.anchor(1));return;
        case"NTT-4B-PROCEDURE":e.checkParagraph(e.anchor(0));return;
        case"NTT-5D-PROCEDURE":e.checkParagraph(e.anchor(0));return;
        case"NTT-6-PROCEDURE":e.checkParagraph(272);e.checkParagraph(363);return;
        case"NTT-7-PROCEDURE":e.checkParagraph(379);e.checkParagraph(381);return;
        case"sct-issue-alternative":if("L10Pro".equals(value)){e.removeBranchRange(229,387);e.checkParagraph(225);}else{e.removeBranchRange(58,225);e.checkParagraph(387);}e.pOptional(55,"");return;
        case"sct-pricing-return-alternative":if("L10Pro".equals(value)){e.removeBranchRange(516,542);e.checkParagraph(493);e.removeEditorialPrefix(478,479);e.removeEditorialPrefix(508,509);}else{e.removeBranchRange(478,514);e.checkParagraph(524);e.removeEditorialPrefix(516,518);e.removeEditorialPrefix(533,535);}e.pOptional(476,"");return;
        case"sct-shared-bill-list":e.billDistribution(DraftBusinessRules.list(value),String.valueOf(v.get("electronicTendering")));return;
        case"sct-bill-body-summary":case"sct-project-bq-sor-placement":e.coveredBy("sct-shared-bill-list");return;
        case"sct-envelope-price-documents":if("not_adopted".equals(decision)){e.coveredBy("sct-tender-system");return;}if("L10Pro".equals(value)){e.removeBranchRange(685,717);e.removeEditorialPrefix(642,644);e.removeEditorialPrefix(668,670);}else{e.removeBranchRange(642,681);e.removeEditorialPrefix(685,687);e.removeEditorialPrefix(708,710);}return;
        case"sct-envelope-bill-range":if("not_adopted".equals(decision)){e.coveredBy("sct-tender-system");return;}e.coveredBy("sct-envelope-price-documents");if(e.reason!=null)return;String numbers=joinValue(value);int ordinary="L10Pro".equals(v.get("electronicTendering"))?674:697;String tail="L10Pro".equals(v.get("electronicTendering"))?" generated by the L10Pro program":"";e.p(ordinary,DraftBusinessRules.list(value).isEmpty()?"(ii) No other ordinary Bills of Quantities":"(ii)Bills No. "+numbers+tail);return;
        case"sct-original-hardcopy-wording":{if("not_adopted".equals(decision)){e.coveredBy("sct-tender-system");return;}String original=e.standard(760);if(original.isEmpty()||!original.contains("Bill of Quantities")){e.fail("The original-hardcopy qualifier target requires actual source mapping.");return;}if("L10Pro".equals(value)){String edited=original.replace(" *and the Bill of Quantities and General Summary","");if(edited.equals(original)){e.fail("The starred BQ / General Summary phrase is not matched.");return;}e.p(760,edited);}else e.p(760,original.replace(" *and"," and"));e.pOptional(771,"");e.pOptional(772,"");return;}
        case"sct-domestic-entire-clause":if("not_used".equals(decision))e.whole("SCT4","SCT4 Not used");else{e.checkParagraph(554);e.pOptional(547,"");}return;
        case"sct-payment-option":if("not_adopted".equals(decision)){e.coveredBy("sct-domestic-entire-clause");return;}if("Option 2".equals(value)){e.p(572,"");e.p(575,e.standard(575).replaceFirst("^\\*",""));}else{e.p(575,"");e.p(572,e.standard(572).replaceFirst("^\\*",""));}e.pOptional(573,"");e.pOptional(576,"");return;
        case"sct-tender-system":if("amend".equals(decision))e.whole("SCT5",String.valueOf(value));else e.checkParagraph(582);return;
        case"sct-envelope-foundation":case"sct-envelope-site-formation":case"sct-envelope-domestic":case"sct-envelope-special-payment":{
            if("not_adopted".equals(decision)){e.coveredBy("sct-tender-system");return;}int[]ps=manualParagraphs(id);if("delete".equals(decision)){e.removeRows(ps);return;}
            Map<String,Object>letters=DraftBusinessRules.asMap(DraftBusinessRules.asMap(e.plan.get("derived")).get("sct5SubmissionLetters"));String original=id.equals("sct-envelope-foundation")?"e":id.equals("sct-envelope-site-formation")?"f":id.equals("sct-envelope-domestic")?"g":"h";Object mapped=letters.get(original);if(mapped==null){e.fail("Final SCT5 row lettering is unresolved.");return;}e.p(ps[0],e.standard(ps[0]).replaceFirst("^#?\\("+original+"\\)","("+mapped+")"));return;}
        case"sct-foundation-assessment":if("delete".equals(decision))e.p(e.anchor(0),"");else e.checkParagraph(e.anchor(0));e.pOptional(e.guidance(0),"");return;
        case"sct-domestic-proposals":if("not_used".equals(decision)){e.p(862,"(2) Not used");e.pOptional(864,"");e.pOptional(871,"");}else e.checkParagraph(862);e.pOptional(859,"");return;
        case"sct-domestic-payment-proposal":if("not_adopted".equals(decision)){e.coveredBy("sct-domestic-proposals");return;}e.checkParagraph(e.anchor(0));return;
        case"sct-domestic-submission-liability":if("not_used".equals(decision))e.p(898,"(c) Not used");else if("amend".equals(decision))e.p(898,e.standard(898).replace("(#and the submission for special terms of payment)","").replaceFirst("^\\*",""));else e.checkParagraph(898);e.pOptional(895,"");e.pOptional(899,"");return;
        case"sct-structural-submission-signature":if("not_used".equals(decision))e.p(e.anchor(0),"(d) Not used");else e.p(e.anchor(0),e.standard(e.anchor(0)).replaceFirst("^\\*",""));e.pOptional(e.guidance(0),"");return;
        case"sct-photocopy-rates":if("not_adopted".equals(decision)){e.coveredBy("sct-tender-system");return;}Map<String,Object> rates=DraftBusinessRules.asMap(value);e.p(751,e.standard(751).replace("#$8.7","$"+rates.get("upToA3")).replace("#$45","$"+rates.get("aboveA3")));e.pOptional(752,"");return;
        case"sct-architect-contact":{Map<String,Object>x=DraftBusinessRules.asMap(value);String p=e.standard(e.anchor(0));p=p.replaceFirst("A/\\s*\\(\\*Mr/Ms\\s*\\)",Matcher.quoteReplacement(String.valueOf(x.get("post"))+" ("+DraftArchitectContactEvidence.renderedName(String.valueOf(x.get("title")),String.valueOf(x.get("name")))+")"));p=p.replaceFirst("telephone\\s*\\.",Matcher.quoteReplacement("telephone "+x.get("phone")+"."));if(p.equals(e.standard(e.anchor(0))))e.fail("Architect placeholder pattern does not match the actual template.");else e.p(e.anchor(0),p);e.pOptional(e.guidance(0),"");return;}
        case"sct-specification-inspection-address":case"sct-drawings-inspection-address":{int pn=e.anchor(0);Map<String,Object>x=DraftBusinessRules.asMap(value);String p=e.standard(pn).replaceFirst("[.…]+\\s*floor",Matcher.quoteReplacement(String.valueOf(x.get("floor"))+" floor")).replaceFirst("Block\\s*[.…]+",Matcher.quoteReplacement("Block "+DraftInputRules.blockIdentifier(String.valueOf(x.get("block")))));e.p(pn,p);e.pOptional(e.guidance(0),"");return;}
        case"sct-site-inspection":{Map<String,Object>x=DraftBusinessRules.asMap(value);String p=e.standard(e.anchor(0)).replaceFirst("\\*\\.+",Matcher.quoteReplacement(x.get("start")+" and "+x.get("end")));StringJoiner restrictions=new StringJoiner("\n");for(Object r:DraftBusinessRules.list(x.get("restrictions"))){Object t=r instanceof Map?DraftBusinessRules.asMap(r).get("text"):r;if(t instanceof String&&DraftBusinessRules.answered(t)){String additional=additionalSiteRestriction(p,(String)t);if(!additional.isEmpty())restrictions.add(additional);}}e.p(e.anchor(0),p+(restrictions.length()>0?"\n"+restrictions:""));e.pOptional(e.guidance(0),"");return;}
        case"sct-precast-payment-clause":if("not_used".equals(decision))e.whole("SCT10","SCT10 Not used");else{e.checkParagraph(1017);e.pOptional(1016,"");}return;
        case"sct-wto-applicability":if("not_used".equals(decision))e.whole("SCT14","SCT14 Not used");else{e.checkParagraph(1104);e.pOptional(1105,"");}return;
        case"sct-special-payment-submission-reference":if("not_adopted".equals(decision)){e.coveredBy("sct-precast-payment-clause");return;}for(Object parent:DraftBusinessRules.list(e.plan.get("actions")))if("sct-tender-system".equals(DraftBusinessRules.asMap(parent).get("id"))&&Boolean.TRUE.equals(DraftBusinessRules.asMap(parent).get("manual"))){e.fail("A whole SCT5 amendment requires the adopted final submission reference, not lettering derived from the replaced standard rows.");return;}for(String dependency:Arrays.asList("sct-envelope-foundation","sct-envelope-site-formation","sct-envelope-domestic","sct-envelope-special-payment"))e.coveredBy(dependency);if(e.reason!=null)return;e.p(1017,e.standard(1017).replace("SCT5(3)(h)",String.valueOf(value)));return;
        case"sct-volumetric-pse-submissions":e.coveredBy("sct-additional-submissions");return;
        case"sct-additional-submissions":if("not_adopted".equals(decision)){e.checkParagraph(901);return;}StringJoiner requirements=new StringJoiner("\n");int next='d';for(Object r:DraftBusinessRules.list(value)){String t=String.valueOf(DraftBusinessRules.asMap(r).get("text"));if(DraftBusinessRules.answered(t))requirements.add("("+(char)next+++") "+t);}e.insertBeforeParagraph(862,requirements.toString());return;
        case"scc-structural-warranty":if("amend".equals(decision)){e.p(570,e.standard(570).replace("ten/twenty*",String.valueOf(value)));e.pOptional(554,"");e.pOptional(572,"");}else e.fail("The source does not establish a universal non-adoption output form. Adopt the exact negative target action.");return;
        case"scc-waterproof-warranty":{if(!"amend".equals(decision)){e.fail("Adopt the exact non-adoption output form for the warranty clause.");return;}List<Object> installations=DraftBusinessRules.list(value);String[] kinds={"Roofing Installation","Refuge Floor Installation","Waterproofing System Installation"};int[] rows1={608,610,612},rows2={618,620,622};int active=0;for(int i=0;i<kinds.length;i++)if(installations.contains(kinds[i]))active++;int index=0;for(int i=0;i<kinds.length;i++){if(!installations.contains(kinds[i])){e.p(rows1[i],"");e.p(rows2[i],"");}else{index++;String item=e.standard(rows1[i]).replace("#","").replaceAll(", and$|,$",index<active?",":"");e.p(rows1[i],item);e.p(rows2[i],e.standard(rows2[i]).replace("#","").replaceAll("; and$|;$",index<active?";":""));}}List<String> names=new ArrayList<>();for(Object name:installations)names.add("the "+name);String selected=names.size()<2?String.join("",names):joinValue(new ArrayList<>(names.subList(0,names.size()-1)))+" or "+names.get(names.size()-1);for(int n:new int[]{614,626,632,642}){String old=e.standard(n);String updated=old.replace("the Roofing Installation#, the Refuge Floor Installation# or the Waterproofing System Installation#",selected);if(updated.equals(old)){e.fail("The installation reference collection is absent at SCC7.304 P"+n);return;}e.p(n,updated);}e.removeRange(596,604);return;}
        case"scc-additional-loss":{if(!"amend".equals(decision)){e.fail("Adopt the optional loss clause's exact non-adoption output form.");return;}Map<String,Object>scope=DraftBusinessRules.asMap(value);String specified=String.valueOf(scope.get("specified")),relevant=String.valueOf(scope.get("relevant"));String old=e.standard(898);String updated=old.replace("any Section specified in accordance with this Clause SCC8.305 of the Special Conditions of Contract", "Section(s) "+specified+" specified in accordance with this Clause SCC8.305 of the Special Conditions of Contract").replace("the relevant Section specified in accordance with this Clause SCC8.305 of the Special Conditions of Contract", "the relevant Section "+relevant+" specified in accordance with this Clause SCC8.305 of the Special Conditions of Contract");if(updated.equals(old)){e.fail("Actual Specified / relevant Section wording is not matched.");return;}e.p(898,updated);e.removeRange(892,896);return;}
        case"scc-bs-definition":e.p(122,e.standard(122).replaceAll("Specialist Sub-contractor for electrical works,.*?lift and escalator works\\*",Matcher.quoteReplacement(specialistNames(v.get("subcontractors"),true))));return;
        case"scc-bs-types-effective-value":e.p(1393,e.standard(1393).replaceAll("lift Specialist Sub-contract Works,.*?ventilation Specialist Sub-contract Works\\*",Matcher.quoteReplacement(specialistNames(v.get("subcontractors"),false))));return;
        case"scc-bs-types-bs-sums":e.p(1422,e.standard(1422).replaceAll("lift Specialist Sub-contract Works,.*?ventilation Specialist Sub-contract Works\\*",Matcher.quoteReplacement(specialistNames(v.get("subcontractors"),false))));return;
        case"scc-safety-pricing-general":case"scc-safety-pricing-SCC20.302":case"scc-safety-pricing-SCC20.303":case"scc-safety-pricing-SCC20.304":if("not_adopted".equals(decision)){String parent=id.endsWith("general")?null:"scc-specialist-"+id.substring(id.lastIndexOf('-')+1);if(parent!=null)e.coveredBy(parent);else e.fail("General BS safety target needs adopted empty-scope wording.");return;}int safetyP=id.endsWith("general")?1428:id.endsWith("302")?1548:id.endsWith("303")?1663:1777;String safety=e.standard(safetyP);String pricing=safetyPricing(DraftBusinessRules.list(value),id.endsWith("general"));String safetyNew=safety.replaceAll("Bill No\\(s\\)\\. _+ / Schedule No\\(s\\)\\. _+\\*\\* of the Bills of Quantities / schedule of rates\\*\\*|Bill No\\. _+ / Schedule No\\. _+\\* of the Bills of Quantities / schedule of rates\\*",Matcher.quoteReplacement(pricing));if(safetyNew.equals(safety)){e.fail("The exact safety Bill / Schedule placeholders are absent.");return;}e.p(safetyP,safetyNew);return;
        case"scc-general-fluctuation-references":case"scc-advance-fluctuation-references":{
            if("not_adopted".equals(decision)){e.coveredBy("scc-advance-adoption");return;}
            for(String dependency:Arrays.asList("scc-specialist-SCC20.302","scc-specialist-SCC20.303","scc-specialist-SCC20.304"))e.coveredBy(dependency);
            if(e.reason!=null)return;
            boolean general=id.contains("general");int refP=general?1430:1216;String oldRefs=e.standard(refP),refNew;
            if(general)refNew=oldRefs.replaceAll("(?:and )?Clauses (?:SCC20\\.302, SCC20\\.303 and SCC20\\.304|SCC20\\.302, SCC20\\.303, SCC20\\.304) of the Special Conditions of Contract[#*]?",Matcher.quoteReplacement(DraftBusinessRules.list(value).isEmpty()?"":"and Clauses "+englishJoin(DraftBusinessRules.list(value))+" of the Special Conditions of Contract"));
            else {
                List<String> clauses=new ArrayList<>();for(Object ref:DraftBusinessRules.list(value))clauses.add("Clause "+ref);
                String sourceCollection=", Clause SCC20.302, Clause SCC20.303 and Clause SCC20.304 of the Special Conditions of Contract*";
                refNew=oldRefs.replace(sourceCollection,clauses.isEmpty()?"":", "+englishJoin(clauses)+" of the Special Conditions of Contract");
            }
            if(refNew.equals(oldRefs)){e.fail("The specialist reference collection is absent at its actual source position.");return;}
            e.p(refP,refNew);return;
        }
        case"scc-ovt-number-protection":case"scc-ovt-number-liability":case"scc-ovt-number-proviso":if("not_adopted".equals(decision)){e.coveredBy("scc-ovt-protection");return;}int pn=id.endsWith("protection")?1797:id.endsWith("liability")?1799:1801;String tree=e.standard(pn).replaceAll("(?i)(Tree No\\.\\s*)[_*]+",Matcher.quoteReplacement("Tree No. "+value));if(tree.equals(e.standard(pn)))tree=e.standard(pn).replaceAll("(?i)(Tree No\\.\\s*)\\.{2,}\\*?",Matcher.quoteReplacement("Tree No. "+value));if(tree.equals(e.standard(pn))){e.fail("The actual Tree No. blank is not matched.");return;}e.p(pn,tree);return;
        case"scc-ovt-protection":if("amend".equals(decision)){e.whole("SCC22.301",String.valueOf(value));return;}if("retain".equals(decision)){e.checkParagraph(1797);e.removeRange(1783,1795);return;}e.fail("OVT non-adoption output form requires an exact business target action.");return;
        case"scc-mixed-measurement":if("amend".equals(decision)){if("Option A".equals(value))e.removeBranchRange(1029,1061);else e.removeBranchRange(1017,1027);e.removeRange(1007,1015);return;}e.fail("Adopt the exact non-adoption output form for this optional SCC.");return;
        case"scc-design-adjustment":case"scc-design-foundation-warranty":case"scc-design-adverse-ground":case"scc-design-failure-cost":if("amend".equals(decision)){if(id.equals("scc-design-adjustment")){e.checkParagraph(470);e.pOptional(469,"");return;}int p=id.equals("scc-design-foundation-warranty")?550:id.equals("scc-design-adverse-ground")?888:1929;List<Object>components=DraftBusinessRules.list(value);String line=e.standard(p);List<String>terms=new ArrayList<>();if(components.contains("piling"))terms.add("piles");if(components.contains("pilecaps"))terms.add("pilecaps");if(components.contains("footings"))terms.add("shallow foundations including footings (raft/pad)");String phrase=String.join(", ",terms);line=line.replace("piles, pilecaps and shallow foundations including footings (raft/pad)*",phrase);if(phrase.isEmpty()){e.fail("No applicable foundation component is adopted.");return;}e.p(p,line);return;}e.fail("Adopt the optional clause's exact negative output form.");return;
        case"scc-section-enhancement":if("amend".equals(decision)){e.p(1082,e.standard(1082).replace("_________*",joinValue(value)));e.removeRange(1074,1078);return;}e.fail("Optional SCC11.304 non-adoption output form is unresolved.");return;
        case"scc-weather-8303":if("amend".equals(decision)){for(int p:new int[]{845,855,869,871}){String old=e.standard(p);if(!old.contains("___")){e.fail("The six actual demolition Section blanks are not present.");return;}e.p(p,old.replaceAll("_+\\*?",Matcher.quoteReplacement(String.valueOf(value))));}e.removeRange(833,839);return;}e.fail("Optional weather clause non-adoption output form is unresolved.");return;
        case"scc-training-numbering":if("not_adopted".equals(decision)){e.coveredBy("scc-demolition-training");return;}if(String.valueOf(value).startsWith("(3)")){for(int p=939;p<=951;p++){String old=e.standard(p);String edited=old.replace("(3)","(5)").replace("(4)","(6)").replace("8.2(3)","8.2(5)");if(!old.equals(edited))e.p(p,edited);}}else e.checkParagraph(939);return;
        case"scc-definition-collection":{for(String dependency:Arrays.asList("scc-all-provisional-definition","scc-ovt-definitions","scc-permit-definition"))e.coveredBy(dependency);if(e.reason!=null)return;String old=e.standard(244);String replaced=old.replaceAll("SCC1\\.301\\s+to\\s+SCC1\\.307",Matcher.quoteReplacement(joinValue(value))).replace("Clauses *","Clauses ");if(replaced.equals(old)){e.fail("Definition collection source pattern is not matched.");return;}e.p(244,replaced);e.pOptional(246,"");return;}
        case"scc-technical-proposal-reference":{String old=e.standard(e.anchor(0));Map<String,Object>x=DraftBusinessRules.asMap(value);String edited=old.replaceAll("Envelope\\s*_+\\*?",Matcher.quoteReplacement("Envelope "+x.get("envelope"))).replaceAll("SCT\\s*_+\\*?",Matcher.quoteReplacement(String.valueOf(x.get("submissionClause"))));if(edited.equals(old)){e.fail("Technical proposal placeholders are absent from the actual definition.");return;}e.p(e.anchor(0),edited);e.pOptional(e.guidance(0),"");return;}
        case"scc-advance-repayment-standard":if("not_adopted".equals(decision)){e.coveredBy("scc-advance-adoption");return;}Map<String,Object>repay=DraftBusinessRules.asMap(value);String r=e.standard(1220).replace("#six",ordinalWord(repay.get("months"),false)).replace("#seventh",ordinalWord(repay.get("firstCertificate"),true));if(r.equals(e.standard(1220))){e.fail("Actual repayment placeholders are absent; guidance text is not the target.");return;}e.p(1220,r);e.pOptional(1214,"");return;
        case"scc-railway-plan-inspection":if("not_adopted".equals(decision)){e.coveredBy("scc-railway-adoption");return;}String rp=e.standard(1977);if("delete".equals(decision)){int split=rp.lastIndexOf("*The railway protection plans");if(split<0){e.fail("The last inspection sentence anchor is not established.");return;}e.p(1977,rp.substring(0,split).trim());}else e.p(1977,rp.replace("*The railway protection plans","The railway protection plans"));e.pOptional(1979,"");return;
        case"scc-railway-eot-option":if("not_adopted".equals(decision)){e.coveredBy("scc-railway-adoption");return;}if("Option A".equals(value))e.removeRange(2022,2026);else e.removeRange(2016,2020);return;
        default:
            if("retain".equals(decision)){e.check();return;}
            if("not_adopted".equals(decision)&&id.endsWith("external-crosschecks")){return;}
            e.fail("This source-specific target requires an adopted text/range mapping before it can be written. Original text is retained.");
        }
    }
    /** Remove only a complete duplicate of the application sentence already retained in SCT8. */
    private static String additionalSiteRestriction(String retainedParagraph,String restriction){
        Matcher application=Pattern.compile("\\bby written application\\b[^.]*\\.").matcher(retainedParagraph);
        if(!application.find())return restriction;
        String retainedSentence=application.group();
        if(application.find())return restriction;
        Matcher duplicate=Pattern.compile("(?:^|(?<=[.!?])\\s+)"+Pattern.quote(retainedSentence)+"(?=\\s|$)").matcher(restriction);
        return duplicate.find()?duplicate.replaceAll("").trim():restriction;
    }
    private static String joinValue(Object value){StringJoiner j=new StringJoiner(", ");for(Object o:DraftBusinessRules.list(value))j.add(String.valueOf(o));return j.toString();}
    private static String englishJoin(List<?> values){if(values.isEmpty())return "";if(values.size()==1)return String.valueOf(values.get(0));return joinValue(new ArrayList<>(values.subList(0,values.size()-1)))+" and "+values.get(values.size()-1);}
    private static String specialistNames(Object value,boolean contractors){List<String> names=new ArrayList<>();for(Object o:DraftBusinessRules.list(value)){String trade=String.valueOf(o).toLowerCase(Locale.ROOT);if(trade.equals("electrical"))trade="electrical";else if(trade.equals("air-conditioning and mechanical ventilation"))trade="air-conditioning and mechanical ventilation";else if(trade.equals("lift and escalator"))trade="lift and escalator";if(contractors)names.add("Specialist Sub-contractor for "+trade+" works");else names.add(trade+" Specialist Sub-contract Works");}return englishJoin(names);}
    private static String safetyPricing(List<Object> rows,boolean plural){List<String> bq=new ArrayList<>(),sor=new ArrayList<>();for(Object o:rows){Map<String,Object>r=DraftBusinessRules.asMap(o);("SOR".equals(r.get("type"))?sor:bq).add(String.valueOf(r.get("number")));}List<String> out=new ArrayList<>();if(!bq.isEmpty())out.add("Bill No"+(plural||bq.size()>1?"(s)":"")+". "+englishJoin(bq)+" of the Bills of Quantities");if(!sor.isEmpty())out.add("Schedule No"+(plural||sor.size()>1?"(s)":"")+". "+englishJoin(sor)+" of the schedule of rates");return englishJoin(out);}
    private static String ordinalWord(Object value,boolean ordinal){long n=(long)Double.parseDouble(String.valueOf(value));String[] cardinal={"zero","one","two","three","four","five","six","seven","eight","nine","ten"};String[] order={"zeroth","first","second","third","fourth","fifth","sixth","seventh","eighth","ninth","tenth"};if(n>=0&&n<cardinal.length)return ordinal?order[(int)n]:cardinal[(int)n];if(!ordinal)return String.valueOf(n);long hundred=n%100;String suffix=hundred>=11&&hundred<=13?"th":n%10==1?"st":n%10==2?"nd":n%10==3?"rd":"th";return n+suffix;}
    private static void removeApplicationIssue(Map<String,Object>plan,String id){DraftBusinessRules.list(plan.get("unresolved")).removeIf(o->("application-"+id).equals(DraftBusinessRules.asMap(o).get("id")));}
    private static void unresolved(Map<String,Object>plan,Map<String,Object>a,String reason){String id="application-"+a.get("id");for(Object o:DraftBusinessRules.list(plan.get("unresolved")))if(id.equals(DraftBusinessRules.asMap(o).get("id")))return;DraftBusinessRules.list(plan.get("unresolved")).add(DraftBusinessRules.map("id",id,"actionId",a.get("id"),"kind","SourceTarget","document",a.get("document"),"clause",a.get("clause"),"inputKeys",a.get("inputKeys"),"message",DraftBusinessRules.copy(reason,"目标原文尚未安全改写；请在本条采用准确动作或改文，原文已保留。")));}
    private static class Editor {
        String text,reason;boolean changed;final String document;final Map<String,Object>action,plan;final Map<Integer,String>current;
        final List<SourceChange> changes=new ArrayList<>();
        Editor(String text,String doc,Map<String,Object>a,Map<String,Object>p,Map<Integer,String>current){this.text=text;document=doc;action=a;plan=p;this.current=new HashMap<>(current);}
        int anchor(int index){return DraftSourceAnchors.target(String.valueOf(action.get("id"))).paragraph(index);}
        int guidance(int index){return DraftSourceAnchors.target(String.valueOf(action.get("id"))).guidanceParagraph(index);}
        String standard(int n){return current.getOrDefault(n,DraftBusinessRules.standardParagraph(document,n));}
        void fail(String r){if(reason==null)reason=r;}
        Pattern exact(String source){String[]parts=source.trim().split("(?U)\\s+|(?<=[.)])(?=[\\p{L}#*])|(?<=\\d)(?=\\p{L})|(?<=\\))(?=\\S)|(?<=#)(?=\\p{L})|(?=-)|(?<=-)");StringJoiner j=new StringJoiner("\\s*");for(String part:parts)j.add(Pattern.quote(part));return Pattern.compile(j.toString(),Pattern.UNICODE_CHARACTER_CLASS);}
        void checkParagraph(int n){String src=standard(n);if(src.trim().isEmpty()||!exact(src).matcher(text).find())fail("Standard "+document+" P"+n+" does not match the project's uploaded source.");}
        void p(int n,String replacement){String src=standard(n);if(src.trim().isEmpty()){fail("No source text for "+document+" P"+n);return;}String before=text;replace(src,replacement,false);if(!before.equals(text)){current.put(n,replacement);changes.add(new SourceChange(n,replacement,false,false));}}
        void pOptional(int n,String replacement){String src=standard(n);if(!src.trim().isEmpty()){String before=text;replace(src,replacement,true);if(!before.equals(text)){current.put(n,replacement);changes.add(new SourceChange(n,replacement,false,false));}}}
        /** Repeated primary/continued headings are bound to their exact alternative note. */
        void removeEditorialPrefix(int n,int following) {
            String src=standard(n),context=standard(following);if(!src.startsWith("*")||context.trim().isEmpty()){fail("The adopted editorial marker and its source context are absent.");return;}
            Matcher m=Pattern.compile("(?<target>"+exact(src).pattern()+")"+gap()+exact(context).pattern(),Pattern.UNICODE_CHARACTER_CLASS).matcher(text);
            if(!m.find()){fail("The adopted editorial heading and its exact alternative note are not located.");return;}int start=m.start("target");
            if(m.find()){fail("The editorial heading and its source context occur more than once.");return;}
            text=text.substring(0,start)+text.substring(start+1);current.put(n,src.substring(1));changes.add(new SourceChange(n,src.substring(1),false,false));changed=true;
        }
        void removeBranchRange(int start,int end){int first=changes.size();removeRange(start,end);if(reason==null){while(changes.size()>first)changes.remove(changes.size()-1);changes.add(new SourceChange(start,end));}}
        void removeRows(int[] paragraphs){int first=changes.size();for(int n:paragraphs)p(n,"");if(reason==null){while(changes.size()>first)changes.remove(changes.size()-1);changes.add(new SourceChange(paragraphs));}}
        void replace(String src,String replacement,boolean optional){Matcher m=exact(src).matcher(text);if(!m.find()){if(!optional)fail("Source paragraph not located uniquely: "+document+" / "+src.substring(0,Math.min(65,src.length())));return;}int start=m.start(),end=m.end();if(m.find()){if(!optional)fail("Source paragraph occurs more than once; target mapping required.");return;}if(!optional)action.putIfAbsent("sourceText",text.substring(start,end));text=text.substring(0,start)+replacement+text.substring(end);changed=true;}
        String gap(){return "[\\s|]*(?:(?:\\((?:\\d+|[a-z]|[ivx]+)\\)|\\d+\\.)[ \\t]*)*";}
        void removeRange(int start,int end){List<Integer> ps=new ArrayList<>();StringJoiner pattern=new StringJoiner(gap());for(int n=start;n<=end;n++){String src=standard(n);if(src.trim().isEmpty())continue;ps.add(n);pattern.add(exact(src).pattern());}if(ps.isEmpty()){fail("No substantive source paragraphs in target range.");return;}Matcher m=Pattern.compile(pattern.toString(),Pattern.UNICODE_CHARACTER_CLASS).matcher(text);if(!m.find()){fail("Complete source range "+document+" P"+start+"–"+end+" is absent or differs from the uploaded edition.");return;}int a=m.start(),b=m.end();if(m.find()){fail("Complete source range occurs more than once; target mapping is required.");return;}action.putIfAbsent("sourceText",text.substring(a,b));text=text.substring(0,a)+text.substring(b);for(int n:ps){current.put(n,"");if(!isPageFurniture(DraftBusinessRules.standardParagraph(document,n)))changes.add(new SourceChange(n,"",false,false));}changed=true;}
        /** Repeated guidance is safe to remove only when its complete neighbouring source context is unique. */
        void removeRangeBetween(int start,int end,int before,int after){String left=standard(before),right=standard(after);if(left.trim().isEmpty()||right.trim().isEmpty()){fail("Both source-context anchors are required for repeated guidance.");return;}List<Integer>ps=new ArrayList<>();StringJoiner target=new StringJoiner(gap());for(int n=start;n<=end;n++){String src=standard(n);if(!src.trim().isEmpty()){ps.add(n);target.add(exact(src).pattern());}}if(ps.isEmpty()){fail("The repeated guidance target is empty.");return;}String expression=exact(left).pattern()+gap()+"(?<target>"+target+")"+gap()+exact(right).pattern();Matcher m=Pattern.compile(expression,Pattern.UNICODE_CHARACTER_CLASS).matcher(text);if(!m.find()){fail("Complete guidance with its source context "+document+" P"+before+" / P"+start+"–"+end+" / P"+after+" is absent or differs from the uploaded edition.");return;}int a=m.start("target"),b=m.end("target");if(m.find()){fail("The repeated guidance and both source-context anchors occur more than once; target mapping is required.");return;}text=text.substring(0,a)+text.substring(b);for(int n:ps){current.put(n,"");if(!isPageFurniture(DraftBusinessRules.standardParagraph(document,n)))changes.add(new SourceChange(n,"",false,false));}String anchor="P"+before+" / P"+start+"–"+end+" / P"+after;List<Object> anchors=new ArrayList<>(DraftBusinessRules.list(action.get("contextAnchors")));if(!anchors.contains(anchor))anchors.add(anchor);action.put("contextAnchors",anchors);changed=true;}
        void range(int start,int end,String replacement){List<Integer>ps=new ArrayList<>();StringJoiner pattern=new StringJoiner("[\\s|]*");for(int n=start;n<=end;n++){String src=standard(n);if(src.trim().isEmpty()||isPageFurniture(src))continue;ps.add(n);pattern.add(exact(src).pattern());}if(ps.isEmpty()){fail("No substantive rows for target list.");return;}Matcher m=Pattern.compile(pattern.toString(),Pattern.UNICODE_CHARACTER_CLASS).matcher(text);if(!m.find()){fail("The complete Bill list is absent or differs from the uploaded source.");return;}int a=m.start(),b=m.end();if(m.find()){fail("Bill list appears more than once; verify the target scope.");return;}action.putIfAbsent("sourceText",text.substring(a,b));text=text.substring(0,a)+replacement+text.substring(b);for(int n:ps){current.put(n,"");if(!isPageFurniture(DraftBusinessRules.standardParagraph(document,n)))changes.add(new SourceChange(n,"",false,false));}current.put(ps.get(0),replacement);changes.add(new SourceChange(ps.get(0),replacement,false,false));changed=true;}
        void billDistribution(List<Object> bills,String mode){if(!Arrays.asList("L10Pro","Hardcopy").contains(mode)){fail("Bill identities are known, but issue format is unresolved.");return;}List<String> a=new ArrayList<>(),b=new ArrayList<>();for(Object o:bills){Map<String,Object>r=DraftBusinessRules.asMap(o);String type=String.valueOf(r.get("type")),purpose=String.valueOf(r.get("purpose")),placement=String.valueOf(r.get("placement"));if(!Arrays.asList("BQ","SOR").contains(type)||!DraftBusinessRules.answered(r.get("purpose"))||!DraftBusinessRules.answered(r.get("placement"))){fail("Adopt the pricing type, purpose and actual placement of every Bill / Schedule before changing distribution.");return;}if("preliminaries".equals(purpose)&&!"1".equals(String.valueOf(r.get("number")))||"preambles".equals(purpose)&&!"2".equals(String.valueOf(r.get("number")))){fail("The standard has additional Bill1 Preliminaries / Bill2 Preambles references. Adopt a complete source mapping before using different role numbers.");return;}String label=("SOR".equals(type)?"Schedule No. ":"Bill No. ")+r.get("number")+" – "+r.get("description");boolean body=Arrays.asList("preliminaries","preambles").contains(purpose);if("standard".equals(placement)&&"SOR".equals(type)){fail("The template has no universal standard SOR placement; adopt its actual Disc / hardcopy arrangement.");return;}if("custom".equals(placement)){fail("Project-specific distribution requires its exact adopted target amendment; the entered raw arrangement is retained.");return;}if("L10Pro".equals(mode)){if("standard".equals(placement)&&body){String collection="preambles".equals(purpose)?"Collection Page":"Collection Pages";a.add(label+" (excluding "+collection+" and Summary Page)");b.add(label+" ("+collection+" and Summary Page only, the other parts are provided in Disc A)");}else if("DiscA".equals(placement))a.add(label);else if("DiscB".equals(placement)||"standard".equals(placement))b.add(label);else{fail("Hardcopy placement under the L10Pro branch requires an exact project distribution amendment.");return;}}else{if("DiscB".equals(placement)){fail("Disc B contains drawings in the hardcopy alternative; adopt exact project wording to put pricing documents there.");return;}if("DiscA".equals(placement)||"standard".equals(placement)&&"preambles".equals(purpose))a.add(label);else b.add(label);}}
          if("L10Pro".equals(mode)){range(132,134,String.join("\n",a));range(166,182,String.join("\n",b));if(a.stream().anyMatch(x->x.startsWith("Schedule No.")))p(130,standard(130).replace("Bills of Quantities:","Bills of Quantities and schedules of rates:"));if(b.stream().anyMatch(x->x.startsWith("Schedule No.")))p(164,standard(164).replace("Bills of Quantities","Bills of Quantities and schedules of rates"));pOptional(189,"");pOptional(190,"");}else{p(322,String.join("\n",a));range(329,343,String.join("\n",b));if(a.stream().anyMatch(x->x.startsWith("Schedule No.")))p(320,standard(320).replace("Bills of Quantities:","Bills of Quantities and schedules of rates:"));if(b.stream().anyMatch(x->x.startsWith("Schedule No.")))p(327,standard(327).replace("Bills of Quantities","Bills of Quantities and schedules of rates"));pOptional(357,"");pOptional(358,"");}}
        boolean isPageFurniture(String t){return t.contains("HD(QS)")||t.matches(".*(?:NTT|SCT|SCC)/\\d+.*")||t.contains("(Cont’d)")||t.contains("(Cont'd)")||t.equals("SPECIAL CONDITIONS OF TENDER")||t.equals("SPECIAL CONDITIONS OF CONTRACT");}
        void insertAfterParagraph(int n,String insertion){String src=standard(n);if(insertion.isEmpty()){checkParagraph(n);return;}String before=text;replace(src,src+"\n"+insertion,false);if(!before.equals(text))changes.add(new SourceChange(n,insertion,true,true));}
        void insertBeforeParagraph(int n,String insertion){String src=standard(n);if(insertion.isEmpty()){checkParagraph(n);return;}String before=text;replace(src,insertion+"\n"+src,false);if(!before.equals(text))changes.add(new SourceChange(n,insertion,true,false));}
        void coveredBy(String id){Map<String,Object>parent=null;for(Object o:DraftBusinessRules.list(plan.get("actions")))if(id.equals(DraftBusinessRules.asMap(o).get("id")))parent=DraftBusinessRules.asMap(o);if(parent==null||"pending".equals(parent.get("action"))||"unresolved".equals(parent.get("application")))fail("Parent target "+id+" remains unresolved; cached child values are excluded.");}
        void check(){String clause=String.valueOf(action.get("clause")).replace(" ","");String root=clause.replaceFirst("[^A-Za-z0-9.].*$","");if(root.startsWith("NTT")){Matcher m=Pattern.compile("NTT(\\d+)").matcher(root);if(m.find()&&section(text,Integer.parseInt(m.group(1)))!=null)return;}if(Pattern.compile(Pattern.quote(root)+"\\b").matcher(text).find())return;Matcher n=Pattern.compile("(?:^|[ ;])P(\\d+)").matcher(String.valueOf(action.get("paragraphs")));if(n.find()){checkParagraph(Integer.parseInt(n.group(1)));return;}fail("No verified target anchor is present for this retained action.");}
        void whole(String clause,String replacement){int[]range=sectionRange(text,clause);if(range==null){fail("The actual full clause "+clause+" is not located.");return;}
            String decision=String.valueOf(action.getOrDefault("decisionAction",action.get("action")));
            if(Boolean.TRUE.equals(action.get("manual"))&&"scc-specialist-SCC20.304".equals(action.get("id"))&&"SCC20.304".equals(clause)&&Arrays.asList("delete","not_used").contains(decision)) {
                Matcher boundary=exact(DraftBusinessRules.standardParagraph("SCC",1779)).matcher(text).region(range[0],range[1]);
                if(!boundary.find()){fail("The verified following SCC22 chapter boundary is absent.");return;}
                int boundaryStart=boundary.start();if(boundary.find()){fail("The following SCC22 chapter boundary is ambiguous.");return;}
                range[1]=boundaryStart;
            }
            String body=text.substring(range[0],range[1]);if(Boolean.TRUE.equals(action.get("manual")))action.put("sourceText",body);else action.putIfAbsent("sourceText",body);int matches=0,last=-1,maxP=0;String remaining=body;for(int n:targetParagraphs(action)){maxP=Math.max(maxP,n);String src=standard(n);if(src.trim().isEmpty())continue;Matcher anchor=exact(src).matcher(body);if(anchor.find()){if(src.length()>=40&&!isPageFurniture(src)&&!src.contains("Guidance")&&!src.contains("Not used"))matches++;last=Math.max(last,anchor.end());remaining=exact(src).matcher(remaining).replaceFirst("");}}if(matches==0){fail("The numbered clause has no matching standard substantive body; verify the uploaded edition.");return;}if(!Boolean.TRUE.equals(action.get("manual"))){for(int n=maxP+1;n<=maxP+15;n++){String src=standard(n);if(!src.trim().isEmpty()&&(src.contains("Guidance")||isPageFurniture(src)))remaining=exact(src).matcher(remaining).replaceFirst("");}remaining=remaining.replaceAll("(?m)^\\s*(?:HD\\(QS\\)[^\\n]*|March 2020[^\\n]*|SPECIAL CONDITIONS OF (?:TENDER|CONTRACT)[^\\n]*|NOTES TO TENDERERS[^\\n]*)$","").replaceAll("\\((?:[a-z]|\\d+)\\)","").replaceAll("[\\s|]","");if(!remaining.isEmpty()){fail("The whole clause contains additional or changed project text; adopt its exact target scope before replacing it.");return;}}int end=Boolean.TRUE.equals(action.get("manual"))?range[1]:range[0]+last;text=text.substring(0,range[0])+replacement+"\n"+text.substring(end);changed=true;for(int n:targetParagraphs(action))current.put(n,"");changes.add(new SourceChange(clause,replacement));}
    }
    private static int[] sectionRange(String text,String clause){if(clause.startsWith("NTT")){try{return section(text,Integer.parseInt(clause.substring(3)));}catch(RuntimeException ex){return null;}}
        Pattern headers=Pattern.compile("(?m)^\\s*\\*?((?:SCT\\d+|SCC\\d+\\.\\d{3}))(?=[A-Za-z \\t])([^\\n]*)$");Matcher m=headers.matcher(text);int start=-1;while(m.find()){String title=m.group(2).trim();if(title.matches(".*\\d+$"))continue;String key=m.group(1);if(start>=0&&!key.equals(clause))return new int[]{start,m.start()};if(key.equals(clause)&&start<0)start=m.start();}return start<0?null:new int[]{start,text.length()};}

    private static int[] section(String text, int number) {
        Matcher m = HEADING.matcher(text);
        int start = -1;
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (start >= 0 && n != number) return new int[]{start, m.start()};
            if (n == number) start = m.start();
        }
        return start < 0 ? null : new int[]{start, text.length()};
    }

    public static String apply(String text, Map<String,Object> decisions) {
        int[] ten = section(text, 10);
        if (ten != null) {
            String body = text.substring(ten[0], ten[1]);
            if (Pattern.compile("\\*?G1\\s*/\\s*\\*?G1a\\b").matcher(body).find()) {
                Object trigger = decisions.get("g1aTrigger");
                if (!(trigger instanceof Boolean)) throw new BizException(4007, "请确认合约类型和39个月工期条件");
                body = body.replaceAll("\\*?G1\\s*/\\s*\\*?G1a\\b", Boolean.TRUE.equals(trigger) ? "G1a" : "G1");
                body = body.replaceAll("(?is)\\(\\s*Use Appendix G1a\\b.*?39 months or more\\s*\\)\\.?", "");
                text = text.substring(0, ten[0]) + body + text.substring(ten[1]);
            }
        }
        int[] thirteen = section(text, 13);
        if (thirteen != null) {
            String body = text.substring(thirteen[0], thirteen[1]);
            if (DraftInputRules.hasNscInstruction(body)) {
                if (Boolean.FALSE.equals(decisions.get("nscApplicable"))) {
                    // Preserve selected works contractors and their sub-subcontractors.
                    body = body.replaceAll("(?i)engagement of Nominated Sub-contractor defined in GCC Clause 1\\.1 and ", "engagement of ");
                    body = body.replaceAll("(?i)sub-contractor of the Nominated Sub-contractor and ", "");
                }
                body = body.replaceAll("(?i)\\(\\s*\\*\\s*Amend\\s+if\\s+NSC\\s+is\\s+not\\s+applicable\\s*\\)", "");
                body = body.replace("*Such requirement", "Such requirement");
                text = text.substring(0, thirteen[0]) + body + text.substring(thirteen[1]);
            }
        }
        return text;
    }

    /** Restore the source-derived wording, so a language model cannot override these rules. */
    public static String protect(String source, String output) {
        for (int number : new int[]{10,13}) {
            int[] original = section(source, number);
            if (original == null) continue;
            int[] generated = section(output, number);
            if (generated == null) throw new BizException(4009, "NTT 第 " + number + " 条在生成结果中缺失，请重试");
            output = output.substring(0, generated[0]) + source.substring(original[0], original[1]) + output.substring(generated[1]);
        }
        return output;
    }

    public static List<String> chunks(String text, int size) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > start + size / 2) end = newline + 1;
            }
            result.add(text.substring(start,end)); start = end;
        }
        return result;
    }

    public static void requireCoverage(String source, String output) {
        if (output.trim().length() < source.trim().length() * 0.65)
            throw new BizException(4009, "生成正文明显缺失模板内容，请重试");
        Set<String> numbers = new HashSet<>();
        Matcher generated = HEADING.matcher(output);
        while (generated.find()) numbers.add(generated.group(1));
        Matcher original = HEADING.matcher(source);
        while (original.find()) if (!numbers.contains(original.group(1)))
            throw new BizException(4009, "生成正文缺失条款 " + original.group(1) + "，请重试");
    }
}
