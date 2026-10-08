package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParseProbe;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import java.util.*;

/** Observation outside VettingCorpus. The original chunker is called exactly once. */
final class VettingCorpusConstructionProbe {
    static List<Chunk> chunks(ConsenseProperties props,String reviewRunId,String projectId,List<SourceDocument> docs) {
        if(!DocumentParseProbe.configured(props))return VettingCorpus.chunks(docs);
        DocumentParseProbe probe=DocumentParseProbe.corpus(props,reviewRunId,projectId);
        boolean completed=false;Throwable operationFailure=null;
        try {
            List<Chunk> chunks;
            try(DocumentParseProbe.Timer timer=probe.measure("original_corpus_chunk_construction",null,"Production VettingCorpus.chunks called once; post-construction observation is separate")) {
                chunks=VettingCorpus.chunks(docs);
            }
            try(DocumentParseProbe.Timer timer=probe.measure("corpus_probe_validation",null,"Observer-only post-construction source/Part/join/union checks; not original chunker time. Measured probe artifact I/O excluded.")) {
            Map<String,SourceDocument> sources=new LinkedHashMap<>();
            for(SourceDocument d:docs)if(sources.put(String.valueOf(d.getId()),d)!=null)throw new IllegalStateException("Corpus probe duplicate source owner ID");
            for(SourceDocument d:docs) {
                String documentId=String.valueOf(d.getId());List<DocumentBlock> blocks=VettingCorpus.blocks(d);
                Map<String,DocumentBlock> byId=new LinkedHashMap<>();
                for(DocumentBlock b:blocks)if(byId.put(b.getId(),b)!=null)throw new IllegalStateException("Corpus probe duplicate original block ID");
                String revision=VettingCorpus.sourceHash(d);
                List<Chunk> owned=new ArrayList<>();List<Map<String,Object>> partChecks=new ArrayList<>();Map<String,List<int[]>> intervals=new LinkedHashMap<>();
                for(Chunk c:chunks)if(documentId.equals(c.getDocumentId())) {
                    owned.add(c);
                    if(!revision.equals(c.getSourceHash())||!VettingCorpus.sourceRole(d).equals(c.getRole()))throw new IllegalStateException("Corpus probe source identity changed");
                    List<String> markers=new ArrayList<>();
                    for(Part p:c.getParts()) {
                        DocumentBlock b=byId.get(p.getBlockId());boolean exact=b!=null&&p.getStartOffset()>=0&&p.getEndOffset()>=p.getStartOffset()
                            &&p.getEndOffset()<=b.getText().length()&&p.getText().equals(b.getText().substring(p.getStartOffset(),p.getEndOffset()));
                        if(!exact)throw new IllegalStateException("Corpus probe Part is not an exact original UTF16 slice");
                        intervals.computeIfAbsent(b.getId(),key->new ArrayList<>()).add(new int[]{p.getStartOffset(),p.getEndOffset()});
                        markers.add(p.getText());
                        partChecks.add(DocumentParseProbe.map("chunkId",c.getId(),"sourceId",documentId,"sourceHash",revision,"blockId",p.getBlockId(),
                            "startOffset",p.getStartOffset(),"endOffset",p.getEndOffset(),"anchor",p.getAnchor(),"exactUtf16Slice",true));
                    }
                    if(!String.join("\n",markers).equals(c.getContent()))throw new IllegalStateException("Corpus probe chunk join differs from original Parts");
                }
                int nonempty=0,originalChars=0,coveredChars=0;List<Map<String,Object>> union=new ArrayList<>();
                for(DocumentBlock b:blocks) {
                    String text=b.getText()==null?"":b.getText();if(JsonUtils.isBlankText(text))continue;
                    nonempty++;originalChars+=text.length();List<int[]> ranges=intervals.getOrDefault(b.getId(),Collections.emptyList());
                    ranges=new ArrayList<>(ranges);ranges.sort(Comparator.comparingInt(a->a[0]));
                    int covered=0,left=-1,right=-1;
                    for(int[] r:ranges) {if(left<0){left=r[0];right=r[1];}else if(r[0]<=right)right=Math.max(right,r[1]);else{covered+=right-left;left=r[0];right=r[1];}}
                    if(left>=0)covered+=right-left;coveredChars+=covered;
                    union.add(DocumentParseProbe.map("blockId",b.getId(),"originalUtf16Chars",text.length(),"coveredUtf16Chars",covered,"uncoveredUtf16Chars",text.length()-covered));
                }
                probe.event("stored_source_chunks",null,DocumentParseProbe.map("sourceId",documentId,"sourceHash",revision,"sourceRole",VettingCorpus.sourceRole(d),
                    "fileName",d.getFileName(),"parseStatus",d.getParseStatus(),"blocks",blocks,"chunks",owned,"partChecks",partChecks,"blockUnion",union,
                    "nonemptyBlocks",nonempty,"originalUtf16Chars",originalChars,"coveredUtf16Chars",coveredChars,
                    "uncoveredUtf16Chars",originalChars-coveredChars,"historicalParseOcrTime",null,"qualifiersComplete","unknown"));
            }
            for(Chunk c:chunks)if(!sources.containsKey(c.getDocumentId()))throw new IllegalStateException("Corpus probe orphan chunk owner");
            }
            probe.event("corpus_construction_finished",null,DocumentParseProbe.map("sourceCount",docs.size(),"chunkCount",chunks.size(),"semanticScopeVerified",false));
            completed=true;return chunks;
        }catch(RuntimeException|Error e){operationFailure=e;probe.originalFailure("corpus_construction_failure",null,e);throw e;}
        finally{probe.finish(completed?"returned_normally":"aborted",operationFailure);}
    }
}
