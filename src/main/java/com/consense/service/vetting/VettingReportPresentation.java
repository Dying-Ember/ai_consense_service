package com.consense.service.vetting;

import com.consense.common.LocalizedText;
import com.consense.web.dto.VettingDtos.*;
import java.util.*;

/** One business table model for Word, PDF and the online report; audit data stays separate. */
public final class VettingReportPresentation {
    private VettingReportPresentation() { }
    public static final class Table {
        private final String title;
        private final List<String> headings;
        private final int[] widths;
        private final List<List<String>> rows = new ArrayList<>();
        private final List<String> findingCodes = new ArrayList<>();
        Table(String title, List<String> headings, int... widths) { this.title=title;this.headings=headings;this.widths=widths; }
        public String getTitle() { return title; }
        public List<String> getHeadings() { return Collections.unmodifiableList(headings); }
        public int[] getWidths() { return widths.clone(); }
        public List<List<String>> getRows() { return Collections.unmodifiableList(rows); }
        public List<String> getFindingCodes() { return Collections.unmodifiableList(findingCodes); }
        void row(String... values) { rows.add(Collections.unmodifiableList(Arrays.asList(values))); }
    }
    public static List<Table> comments(List<FindingVO> findings, CoverageVO coverage) {
        Map<String,Group> groups=new LinkedHashMap<>();
        if(findings!=null) for(FindingVO f:findings) if(f!=null) {
            FindingEvidence source=primary(f);
            String key=nonblank(source==null?null:source.getFileKey(),f.getFileKey());
            String filename=source==null?"":str(source.getFileName());
            String id=source==null?"":str(source.getDocumentId());
            if(coverage!=null&&(filename.isEmpty()||id.isEmpty())) {
                DocumentCoverageVO selected=null;
                for(DocumentCoverageVO d:safe(coverage.getDocuments())) if(d!=null&&(!id.isEmpty()?id.equals(d.getDocumentId()):key.equals(d.getFileKey()))) {
                    if(selected!=null) { selected=null;break; } selected=d;
                }
                if(selected!=null) { filename=nonblank(filename,selected.getFileName());id=nonblank(id,selected.getDocumentId()); }
            }
            String identity=id.isEmpty()?key+"|"+filename:"id:"+id;
            Group group=groups.get(identity);
            if(group==null) { group=new Group(key,filename);groups.put(identity,group); }
            group.findings.add(f);
        }
        List<Group> ordered=new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingInt((Group g)->rank(g.key,g.filename)).thenComparing(g->g.filename));
        List<Table> tables=new ArrayList<>();int[] sectionCounts=new int[9];
        for(Group g:ordered) {
            int section=rank(g.key,g.filename);
            Table table=new Table(section+". "+label(g.key,g.filename),Arrays.asList("", "Page", "Clause / Item", "Comments", "Remarks by Project Team (if any)"),6,11,15,53,15);
            for(FindingVO f:g.findings) {
                FindingEvidence source=primary(f);
                String page=nonblank(source==null?null:source.getPageNo(),f.getPageNo());
                String clause=nonblank(f.getRefs(),f.getLocation());
                String comment=english(f.getBody());
                if(comment.isEmpty())comment=english(f.getTitle());
                if(!english(f.getImpact()).isEmpty())comment+="\nReason and impact: "+english(f.getImpact());
                if(!str(f.getExpected()).isEmpty())comment+="\nExpected basis: "+f.getExpected();
                if(!english(f.getSuggestion()).isEmpty())comment+="\nSuggested action: "+english(f.getSuggestion());
                String remarks=str(f.getReviewRemarks());
                if(!str(f.getActionTaken()).isEmpty())remarks+=(remarks.isEmpty()?"":"\n")+"Action taken: "+f.getActionTaken();
                if(f.getAddendumRequired()!=null)remarks+=(remarks.isEmpty()?"":"\n")+"Include in tender addendum: "+(f.getAddendumRequired()?"Required":"Not required");
                table.row(section+"."+(++sectionCounts[section]),page.isEmpty()?"Not located":page,clause.isEmpty()?"Not located":clause,comment,remarks);
                table.findingCodes.add(str(f.getCode()));
            }
            tables.add(table);
        }
        if(tables.isEmpty()) {
            Table t=new Table("Comments recorded",Arrays.asList("", "Page", "Clause / Item", "Comments", "Remarks by Project Team (if any)"),6,11,15,53,15);
            t.row("-","-","-","No findings were recorded by this task. This does not establish that the documents have no issues. See the review scope and limitations in Appendix II.","");tables.add(t);
        }
        return tables;
    }
    public static Table evidenceIndex(List<FindingVO> findings,CoverageVO coverage) {
        Table index=new Table("Comment and evidence index",Arrays.asList("Comment","Evidence record","Document / Clause"),12,28,60);
        for(Table table:comments(findings,coverage))for(int i=0;i<table.findingCodes.size();i++)
            index.row(table.rows.get(i).get(0),table.findingCodes.get(i),table.title+" / "+table.rows.get(i).get(2));
        return index;
    }
    public static List<Table> checklist(List<VettingChecklistCatalog.Result> results) {
        Map<String,VettingChecklistCatalog.Result> manual=new HashMap<>();
        for(VettingChecklistCatalog.Result r:safe(results))if(r!=null)manual.put(r.getId(),r);
        List<Table> tables=new ArrayList<>();String section=null;Table table=null;
        for(VettingChecklistCatalog.Item item:VettingChecklistCatalog.items()) {
            if(!item.getSection().equals(section)) {
                section=item.getSection();
                table=item.getTemplatePage()==14
                        ?new Table(section,Arrays.asList("No.","Common examples for different types of builder's works for building services installations specified in building tenders","Suggested actions to be taken by the Contract Team"),6,44,50)
                        :new Table(section,Arrays.asList(item.getSection().startsWith("BILL NO.")?"Bills of Quantities":"PSS Clause", "Description", "Remarks by Contract Team"),28,52,20);
                tables.add(table);
            }
            VettingChecklistCatalog.Result r=manual.get(item.getId());
            String status=r==null?"unchecked":str(r.getStatus());
            String remarks="fulfilled".equals(status)?"Checked and fulfilled":"not_applicable".equals(status)?"Not applicable":"Not checked - human confirmation required";
            String scopeNote=reportScopeNote(item);
            if(!scopeNote.isEmpty())remarks+="\n"+scopeNote;
            if(r!=null&&!str(r.getRemarks()).isEmpty())remarks+="\n"+r.getRemarks();
            if(item.getTemplatePage()==14) {
                String[] parts=item.getDescription().split(" Suggested actions: ",2);
                table.row(item.getClause(),parts[0],(parts.length>1?parts[1]+"\n":"")+"Contract team confirmation: "+remarks);
            } else table.row(item.getClause(),item.getDescription(),remarks);
        }
        return tables;
    }
    /** Full scope guidance is in the report introduction/API; compact row notes preserve the sample layout. */
    private static String reportScopeNote(VettingChecklistCatalog.Item item) {
        if("pricing_document_excluded".equals(item.getReviewScope()))return "Outside scope: pricing-document countercheck.";
        if("mixed_contract_pricing".equals(item.getReviewScope()))return "PRE / contract wording only. Pricing excluded.";
        return "";
    }
    private static FindingEvidence primary(FindingVO f) {
        for(FindingEvidence e:safe(f.getEvidence()))if(e!=null&&"source".equalsIgnoreCase(e.getSide()))return e;
        for(FindingEvidence e:safe(f.getEvidence()))if(e!=null)return e;
        return null;
    }
    private static int rank(String key,String filename) {
        String k=key.toUpperCase(Locale.ROOT),n=filename.toLowerCase(Locale.ROOT);
        if(k.equals("NTT")||n.contains("ntt"))return 1;
        if(k.equals("GCT")||n.contains("gct"))return 2;
        if(k.equals("SCT")||n.contains("sct"))return 3;
        if(k.equals("FT")||k.equals("FOT")||n.contains("fot"))return 4;
        if(k.equals("AA")||k.equals("AOA")||n.contains("aoa"))return 5;
        if(k.equals("SCC")||k.equals("GCC")||k.equals("COC")||n.contains("scc")||n.contains("coc"))return 6;
        if(k.equals("PSS")||k.equals("PRE")||k.equals("SPEC")||n.contains("specification")||n.contains("pre ("))return 7;
        return 8;
    }
    private static String label(String key,String filename) {
        String[] names={"", "Notes to Tenderers", "General Conditions of Tender", "Special Conditions of Tender", "Form of Tender", "Articles of Agreement", "Conditions of Contract", "Project Specific Specification", "Other tender documents"};
        String title=names[rank(key,filename)];return title+(filename.isEmpty()?" ["+key+"]":" - "+filename);
    }
    private static String english(LocalizedText value) { return value==null?"":str(value.pick("en")); }
    private static String nonblank(String a,String b) { return str(a).isEmpty()?str(b):str(a); }
    private static String str(String s) { return s==null?"":s.trim(); }
    private static <T> List<T> safe(List<T> list) { return list==null?Collections.emptyList():list; }
    private static final class Group { final String key,filename;final List<FindingVO> findings=new ArrayList<>();Group(String key,String filename){this.key=key;this.filename=filename;} }
}
