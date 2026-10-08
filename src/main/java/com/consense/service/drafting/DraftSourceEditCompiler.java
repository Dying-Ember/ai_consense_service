package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.document.DocxTemplateEditor.*;
import java.util.*;
import java.util.regex.*;

/** Compiles semantic paragraph events; it never receives or diffs a final flattened document. */
final class DraftSourceEditCompiler implements DraftClauseRules.SourceListener {
    private final TemplateIndex source;private final String document;
    private Map<Integer,Desired> desired=new LinkedHashMap<>();private List<Insertion> insertions=new ArrayList<>();
    private List<Removal> removals=new ArrayList<>();
    private static final class Removal {final String id;final List<NodeAnchor> nodes;Removal(String id,List<NodeAnchor> nodes){this.id=id;this.nodes=nodes;}}
    private static final class Desired {final Paragraph p;final String value,id;Desired(Paragraph p,String value,String id){this.p=p;this.value=value;this.id=id;}}
    private static final class Insertion {
        final Paragraph p,donor;final String value,id;final boolean after;
        Insertion(Paragraph p,String value,String id,boolean after){this(p,value,id,after,p);}
        Insertion(Paragraph p,String value,String id,boolean after,Paragraph donor){this.p=p;this.value=value;this.id=id;this.after=after;this.donor=donor;}
    }
    DraftSourceEditCompiler(TemplateIndex source,String document){this.source=source;this.document=document;}
    String projection(){StringJoiner out=new StringJoiner("\n");for(Paragraph p:source.getMainParagraphs())out.add(p.getCatalogText());return out.toString();}
    @Override public void adopted(Map<String,Object> action,List<DraftClauseRules.SourceChange> changes,boolean changed) {
        if(changed&&changes.isEmpty())throw new BizException(4012,"SOURCE_WHOLE_FORMAT_MAPPING_REQUIRED");
        Map<Integer,Desired> trial=new LinkedHashMap<>(desired);List<Insertion> inserted=new ArrayList<>(insertions);List<Removal> removed=new ArrayList<>(removals);String id=String.valueOf(action.get("id"));
        for(DraftClauseRules.SourceChange change:changes) {
            if(change.scopeEnd>0){removed.add(removeBranch(id,change.paragraph,change.scopeEnd));continue;}
            if(change.rowParagraphs!=null){removed.add(removeRows(id,change.rowParagraphs));continue;}
            if(change.clause!=null){whole(trial,inserted,removed,action,id,change.clause,change.replacement);continue;}
            Paragraph p=resolve(change.paragraph);
            if(change.insertion)inserted.add(new Insertion(p,change.replacement,id+"-insert-P"+change.paragraph,change.after));
            else trial.put(p.getOrdinal(),new Desired(p,change.replacement,id+"-P"+change.paragraph));
        }
        compile(trial,inserted,removed); // Factories validate text/control spans before committing this action's state.
        desired=trial;insertions=inserted;removals=removed;
    }
    private Removal removeBranch(String id,int start,int end) {
        return removeBranch(id,start,end,null);
    }
    private Removal removeBranch(String id,int start,int end,String retainedRow) {
        Set<String> selected=new HashSet<>();Set<String> rows=new LinkedHashSet<>();List<NodeAnchor> body=new ArrayList<>();
        for(int ordinal=start;ordinal<=end;ordinal++) {
            Paragraph p=resolve(ordinal);
            if(retainedRow!=null&&retainedRow.equals(p.getRowId()))continue;
            selected.add(p.getId());
            if(p.getRowId()!=null)rows.add(p.getRowId());
            else body.add(source.anchor(p.getId()));
        }
        return removeSelectedRows(id,selected,rows,body);
    }
    /** Only the verified editorial catalog may request a complete guidance-only row. */
    void removeVerifiedGuidanceRow(String id,Paragraph p) {
        if(removedIds(removals).contains(p.getId()))return;
        List<Removal> trial=new ArrayList<>(removals);trial.add(removeRows(id,new int[]{p.getOrdinal()}));
        compile(desired,insertions,trial);removals=trial;
    }
    private Removal removeRows(String id,int[] ordinals) {
        Set<String> selected=new HashSet<>();Set<String> rows=new LinkedHashSet<>();
        for(int ordinal:ordinals){Paragraph p=resolve(ordinal);if(p.getRowId()==null)throw new BizException(4012,"SOURCE_ROW_REQUIRED: "+p.getId());selected.add(p.getId());rows.add(p.getRowId());}
        return removeSelectedRows(id,selected,rows,Collections.emptyList());
    }
    private Removal removeSelectedRows(String id,Set<String> selected,Set<String> rows,List<NodeAnchor> body) {
        Map<String,NodeAnchor> nodes=new LinkedHashMap<>();for(NodeAnchor p:body)nodes.put(p.getId(),p);
        for(String rowId:rows) {
            NodeAnchor row=source.anchor(rowId);
            for(String paragraphId:row.getParagraphIds())if(!selected.contains(paragraphId)) {
                Paragraph p=source.paragraph(paragraphId);
                boolean feature=source.getFeatures().stream().anyMatch(f->f.getStartId().startsWith(p.getId()+"/")||f.getEndId()!=null&&f.getEndId().startsWith(p.getId()+"/"));
                if(!p.isPlainEmpty()||feature)throw new BizException(4012,"SOURCE_ROW_SCOPE_INCOMPLETE: "+rowId);
            }
            nodes.put(rowId,row);
        }
        Set<String> tables=new LinkedHashSet<>();for(String rowId:rows)tables.add(rowId.substring(0,rowId.lastIndexOf('/')));
        for(String tableId:tables) {
            List<NodeAnchor> allRows=new ArrayList<>();for(NodeAnchor node:source.getAnchors())if("tr".equals(node.getKind())&&node.getId().substring(0,node.getId().lastIndexOf('/')).equals(tableId))allRows.add(node);
            if(!allRows.isEmpty()&&allRows.stream().allMatch(row->rows.contains(row.getId()))) {for(NodeAnchor row:allRows)nodes.remove(row.getId());nodes.put(tableId,source.anchor(tableId));}
        }
        if(nodes.isEmpty())throw new BizException(4012,"SOURCE_REMOVAL_SCOPE_EMPTY: "+id);
        return new Removal(id+"-remove-scope",new ArrayList<>(nodes.values()));
    }
    private static boolean globalPageFurniture(String text) {
        String value=text.trim();return value.startsWith("HD(QS)")||value.startsWith("March 2020")||value.startsWith("SPECIAL CONDITIONS OF TENDER")||value.startsWith("SPECIAL CONDITIONS OF CONTRACT")||value.equals("NOTES TO TENDERERS");
    }
    private void whole(Map<Integer,Desired> trial,List<Insertion> inserted,List<Removal> removed,Map<String,Object> action,String id,String clause,String replacement) {
        if(verifiedSpecialistNegative(trial,removed,action,id,clause,replacement))return;
        if(verifiedWtoNegative(removed,action,id,clause,replacement))return;
        List<Paragraph> paragraphs=source.getMainParagraphs();List<Integer> matches=new ArrayList<>();
        for(int i=0;i<paragraphs.size();i++)if(clause.equals(rootHeading(paragraphs.get(i))))matches.add(i);
        if(matches.size()!=1)throw new BizException(4012,"SOURCE_CLAUSE_BOUNDARY_AMBIGUOUS: "+clause);
        int start=matches.get(0),end=paragraphs.size();for(int i=start+1;i<paragraphs.size();i++){String heading=rootHeading(paragraphs.get(i));if(heading!=null&&!clause.equals(heading)){end=i;break;}}
        int first=start;String adopted=replacement;
        if(clause.startsWith("NTT")) {
            String number=clause.substring(3);adopted=adopted.replaceFirst("^"+Pattern.quote(number)+"\\.\\s*","");
            Paragraph numberCell=paragraphs.get(start);
            if("NTT9".equals(clause)&&"9. Not used".equals(replacement)&&"*9.".equals(numberCell.getCatalogText()))
                trial.put(numberCell.getOrdinal(),new Desired(numberCell,"9.",id+"-number-marker"));
            first=start+1;while(first<end&&(paragraphs.get(first).getCatalogText().trim().isEmpty()||furniture(paragraphs.get(first).getCatalogText())))first++;
        }
        if(first>=end)throw new BizException(4012,"SOURCE_CLAUSE_BODY_MISSING: "+clause);
        Paragraph donor=paragraphs.get(first);
        for(int i=first+1;i<end;i++)if(!paragraphs.get(i).getCatalogText().trim().isEmpty()&&!furniture(paragraphs.get(i).getCatalogText())&&!paragraphs.get(i).getCatalogText().contains("Guidance")){donor=paragraphs.get(i);break;}
        String[] lines=adopted.split("\\n",-1);
        Paragraph heading=paragraphs.get(first);
        // Clause number separators and the trailing text outside a bookmark belong to the source layout.
        if(!clause.startsWith("NTT")&&(heading.getText().startsWith(clause+"\t")||heading.getText().startsWith("*"+clause+"\t"))&&lines[0].startsWith(clause)) {
            lines[0]=clause+lines[0].substring(clause.length()).replaceFirst("^\\s+","");
            Matcher tail=Pattern.compile("\\s+$").matcher(heading.getCatalogText());
            if(tail.find())lines[0]+=tail.group();
        }
        for(int i=first;i<end;i++)for(Feature feature:source.getFeatures())if("OMML".equals(feature.getKind())&&feature.getStartId().startsWith(paragraphs.get(i).getId()+"/"))throw new BizException(4012,"SOURCE_FORMULA_SCOPE_REQUIRES_STRUCTURAL_REVIEW: "+clause);
        if("SCT".equals(document)&&Arrays.asList("SCT4","SCT10").contains(clause)&&(clause+" Not used").equals(replacement)) {
            trial.put(heading.getOrdinal(),new Desired(heading,lines[0],id+"-scope-P"+heading.getOrdinal()));
            removed.add(removeBranch(id,heading.getOrdinal()+1,paragraphs.get(end-1).getOrdinal(),heading.getRowId()));
            return;
        }
        for(int i=first;i<end;i++) {
            Paragraph p=paragraphs.get(i);
            if(globalPageFurniture(p.getCatalogText())||p.getCatalogText().trim().isEmpty())continue;
            trial.put(p.getOrdinal(),new Desired(p,i==first?lines[0]:"",id+"-scope-P"+p.getOrdinal()));
        }
        if(lines.length>1)inserted.add(new Insertion(paragraphs.get(first),String.join("\n",Arrays.asList(lines).subList(1,lines.length)),id+"-scope-continuation",true,donor));
    }
    private boolean verifiedWtoNegative(List<Removal> removed,Map<String,Object> action,String id,String clause,String replacement) {
        String decision=String.valueOf(action.getOrDefault("decisionAction",action.get("action")));
        if(!"NTT".equals(document)||!Boolean.TRUE.equals(action.get("manual"))||!"NTT-9-WTO".equals(id)||!"NTT9".equals(clause)
                ||!Arrays.asList("delete","not_used").contains(decision))return false;
        if(!replacement.equals("not_used".equals(decision)?"9. Not used":""))throw new BizException(4012,"SOURCE_NEGATIVE_REPLACEMENT_MISMATCH: "+clause);
        if(!"60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c".equals(source.getSourceSha256()))
            throw new BizException(4012,"SOURCE_STRUCTURAL_CLAUSE_EDITION_UNVERIFIED: "+clause);
        if("not_used".equals(decision))return false; // Reuse the ordinary WTO=false native heading/body mapping.
        removed.add(removeBranch(id,435,471)); // Complete clause rows; retain following source page furniture and NTT10.
        return true;
    }
    /** Only these expressly adopted negative decisions have a reviewed complete formula/table scope. */
    private boolean verifiedSpecialistNegative(Map<Integer,Desired> trial,List<Removal> removed,Map<String,Object> action,String id,String clause,String replacement) {
        String decision=String.valueOf(action.getOrDefault("decisionAction",action.get("action")));
        if(!"SCC".equals(document)||!Boolean.TRUE.equals(action.get("manual"))||!id.equals("scc-specialist-"+clause)
                ||!Arrays.asList("SCC20.303","SCC20.304").contains(clause)||!Arrays.asList("delete","not_used").contains(decision))return false;
        boolean notUsed="not_used".equals(decision);
        if(!replacement.equals(notUsed?clause+" Not used":""))throw new BizException(4012,"SOURCE_NEGATIVE_REPLACEMENT_MISMATCH: "+clause);
        if(!"9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208".equals(source.getSourceSha256()))
            throw new BizException(4012,"SOURCE_STRUCTURAL_CLAUSE_EDITION_UNVERIFIED: "+clause);
        int first="SCC20.303".equals(clause)?1550:1665,last="SCC20.303".equals(clause)?1664:1778;
        // Verify every source paragraph and the following boundary, including equation and table paragraphs.
        // SCC20.304 stops before the SCC22 section heading, rather than at the next numbered sub-clause.
        if(source.getMainParagraphs().size()<=last)throw new BizException(4012,"SOURCE_CLAUSE_SCOPE_INCOMPLETE: "+clause);
        for(int n=first;n<=last+1;n++)if(!source.mainParagraph(n).getCatalogText().equals(DraftBusinessRules.standardParagraph(document,n)))
            throw new BizException(4012,"SOURCE_PARAGRAPH_BINDING_MISMATCH: "+document+" P"+n);
        Paragraph heading=source.mainParagraph(first);
        if(notUsed)trial.put(first,new Desired(heading,clause+" Not used",id+"-scope-P"+first));
        if("SCC20.304".equals(clause)) {
            // P1666 contains only three native bookmark starts whose ends are direct body nodes.
            // Preserve that empty anchor paragraph and all pairs; remove every substantive clause node.
            if(!notUsed)removed.add(removeBranch(id+"-heading",first,first));
            removed.add(removeBranch(id,first+2,last));
        }else removed.add(removeBranch(id,notUsed?first+1:first,last,notUsed?heading.getRowId():null));
        return true;
    }
    private String rootHeading(Paragraph p) {
        String text=p.getCatalogText().trim();
        if(furniture(text))return null;
        if(document.equals("NTT")) {
            if(p.getCellId()!=null&&p.getCellId().matches(".*/[^/]+:tc\\[1\\]$")&&text.matches("\\*?\\d+\\."))return "NTT"+text.replaceAll("[^0-9]","");
            return null;
        }
        Matcher m=Pattern.compile("^\\*?((?:SCT\\d+|SCC\\d+\\.\\d{3}))(?=[A-Za-z \\t])(.+)$").matcher(text);
        return m.find()&&!m.group(2).trim().matches(".*\\d+$")?m.group(1):null;
    }
    private static boolean furniture(String text){return text.contains("HD(QS)")||text.matches(".*(?:NTT|SCT|SCC)/\\d+.*")||text.contains("(Cont’d)")||text.contains("(Cont'd)")||text.equals("SPECIAL CONDITIONS OF TENDER")||text.equals("SPECIAL CONDITIONS OF CONTRACT")||text.equals("NOTES TO TENDERERS")||text.startsWith("March 2020");}
    private Paragraph resolve(int ordinal) {
        String expected=DraftBusinessRules.standardParagraph(document,ordinal);
        if(ordinal>0&&ordinal<=source.getMainParagraphs().size()&&source.mainParagraph(ordinal).getCatalogText().equals(expected))return source.mainParagraph(ordinal);
        List<Paragraph> found=new ArrayList<>();String key=expected.replaceAll("(?U)\\s+","");
        for(Paragraph p:source.getMainParagraphs())if(!key.isEmpty()&&p.getCatalogText().replaceAll("(?U)\\s+","").equals(key))found.add(p);
        if(found.size()!=1)throw new BizException(4012,"SOURCE_PARAGRAPH_BINDING_MISMATCH: "+document+" P"+ordinal);
        return found.get(0);
    }
    Set<String> changedIds(){Set<String> ids=new HashSet<>();for(Desired d:desired.values())ids.add(d.p.getId());for(Removal removal:removals)for(NodeAnchor node:removal.nodes)ids.addAll(node.getParagraphIds());return ids;}
    List<Edit> edits(){return compile(desired,insertions,removals);}
    private List<Edit> compile(Map<Integer,Desired> values,List<Insertion> inserted,List<Removal> removed) {
        List<Edit> edits=new ArrayList<>();Set<String> removedParagraphs=removedIds(removed);
        for(Desired d:values.values()) {
            if(removedParagraphs.contains(d.p.getId()))continue;
            if(d.value.equals(d.p.getCatalogText()))continue;
            if(d.value.isEmpty()) {
                guardClear(d.p);edits.add(Edit.clearParagraphAndNumbering(d.id,d.p));continue;
            }
            String[] lines=d.value.split("\\n",-1);
            edits.addAll(spans(d.id,d.p,lines[0]));
            if(lines.length>1)edits.add(Edit.insertParagraphs(d.id+"-continuation",d.p,true,Arrays.asList(lines).subList(1,lines.length),d.p));
        }
        for(Insertion i:inserted) {
            if(removedParagraphs.contains(i.p.getId()))throw new BizException(4012,"SOURCE_INSERTION_IN_REMOVED_SCOPE: "+i.id);
            edits.add(Edit.insertParagraphs(i.id,i.p,i.after,Arrays.asList(i.value.split("\\n",-1)),i.donor));
        }
        for(Removal removal:removed)edits.add(Edit.removeNodes(removal.id,removal.nodes));
        return edits;
    }
    private static Set<String> removedIds(List<Removal> removed) {
        Set<String> result=new HashSet<>();for(Removal removal:removed)for(NodeAnchor node:removal.nodes)result.addAll(node.getParagraphIds());return result;
    }
    private void guardClear(Paragraph p) {
        for(Feature f:source.getFeatures())if("FIELD".equals(f.getKind())&&(f.getStartId().startsWith(p.getId()+"/")||f.getEndId()!=null&&f.getEndId().startsWith(p.getId()+"/")))throw new BizException(4012,"FIELD_TEXT_CLEAR_UNSUPPORTED: "+p.getId());
    }
    /** Bounded word-token alignment within an explicitly selected source paragraph. */
    static List<Edit> spans(String id,Paragraph p,String replacement) {
        return spans(id,p,replacement,true);
    }
    static List<Edit> editableSpans(String id,Paragraph p,String replacement) {
        // Normalize paired transport line endings before alignment with existing native breaks.
        return spans(id,p,replacement.replace("\r\n","\n"),false);
    }
    private static List<Edit> spans(String id,Paragraph p,String replacement,boolean catalog) {
        String original=catalog?p.getCatalogText():p.getText();if(original.equals(replacement))return Collections.emptyList();
        int tab=p.getText().indexOf('\t');
        if(catalog&&tab>0) {
            String root=p.getText().substring(0,tab);
            String number=root.startsWith("*")?root.substring(1):root;
            if(root.matches("\\*?(?:SCT\\d+|SCC\\d+\\.\\d{3})")&&original.startsWith(root)&&replacement.startsWith(number)) {
                int end=original.replaceFirst("\\s+$","").length();
                String title=replacement.substring(number.length()).replaceFirst("^\\s+","").replaceFirst("\\s+$","");
                List<Edit> headingEdits=new ArrayList<>();
                if(root.startsWith("*"))headingEdits.add(Edit.replaceCatalogSpan(id+"-heading-marker",p,0,1,"*",""));
                String originalTitle=original.substring(root.length(),end);
                if(!originalTitle.equals(title))headingEdits.add(Edit.replaceCatalogSpan(id+"-heading",p,root.length(),end,originalTitle,title));
                return headingEdits;
            }
        }
        List<Token> a=tokens(original),b=tokens(replacement);
        if((long)(a.size()+1)*(b.size()+1)>2000000L)throw new BizException(4012,"PARAGRAPH_AMENDMENT_TOO_LARGE: "+id);
        int[][] lengths=new int[a.size()+1][b.size()+1];
        for(int i=a.size()-1;i>=0;i--)for(int j=b.size()-1;j>=0;j--)lengths[i][j]=a.get(i).value.equals(b.get(j).value)?1+lengths[i+1][j+1]:Math.max(lengths[i+1][j],lengths[i][j+1]);
        List<Gap> gaps=new ArrayList<>();int i=0,j=0,oldStart=0,newStart=0;
        while(i<a.size()&&j<b.size()) {
            if(a.get(i).value.equals(b.get(j).value)) {
                if(oldStart<a.get(i).start||newStart<b.get(j).start)gaps.add(new Gap(oldStart,a.get(i).start,newStart,b.get(j).start));
                oldStart=a.get(i++).end;newStart=b.get(j++).end;
            }else if(lengths[i+1][j]>=lengths[i][j+1])i++;else j++;
        }
        if(oldStart<original.length()||newStart<replacement.length())gaps.add(new Gap(oldStart,original.length(),newStart,replacement.length()));
        List<Gap> bounded=new ArrayList<>();
        for(Gap gap:gaps) {
            if(gap.a==gap.z) {
                if(gap.a>0) {int width=Character.charCount(original.codePointBefore(gap.a));gap.a-=width;gap.b-=width;}
                else if(gap.z<original.length()){int width=Character.charCount(original.codePointAt(gap.z));gap.z+=width;gap.y+=width;}
                else throw new BizException(4012,"EMPTY_SPAN_UNSUPPORTED: "+id);
            }
            if(!bounded.isEmpty()&&gap.a<=bounded.get(bounded.size()-1).z) {Gap previous=bounded.get(bounded.size()-1);previous.z=gap.z;previous.y=gap.y;}
            else bounded.add(gap);
        }
        List<Edit> edits=new ArrayList<>();int n=0;
        for(Gap gap:bounded)edits.add(catalog?Edit.replaceCatalogSpan(id+"-span"+(++n),p,gap.a,gap.z,original.substring(gap.a,gap.z),replacement.substring(gap.b,gap.y)):Edit.replaceSpan(id+"-span"+(++n),p,gap.a,gap.z,original.substring(gap.a,gap.z),replacement.substring(gap.b,gap.y)));
        return edits;
    }
    private static final class Token {final int start,end;final String value;Token(Matcher m){start=m.start();end=m.end();value=m.group();}}
    private static List<Token> tokens(String text){List<Token> out=new ArrayList<>();Matcher m=Pattern.compile("(?U)\\t|\\r|\\n|[^\\S\\t\\r\\n]+|[\\p{L}\\p{N}_]+|[^\\p{L}\\p{N}_\\s]").matcher(text);while(m.find())out.add(new Token(m));return out;}
    private static final class Gap {int a,z,b,y;Gap(int a,int z,int b,int y){this.a=a;this.z=z;this.b=b;this.y=y;}}
}
