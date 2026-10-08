package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import lombok.Data;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Observed original-block slices only. Transport coverage cannot prove legal completeness. */
public final class VettingBlockContinuation {
    public static final String POLICY = "initial_ranked_source_block_observed_ranges_v1";
    private static final Pattern OFFSET = Pattern.compile("^(.*) @(\\d+)$");
    private final Map<List<String>,List<Member>> members = new LinkedHashMap<>();

    @Data public static class Trace {
        private String originId, sourceIdentity, blockId, sourceAnchor;
        private String status = "unknown", admissionStatus = "not_admitted", coverage = "observed_corpus_ranges_only", qualifiersComplete = "unknown";
        private List<String> requiredChunkIds = new ArrayList<>(), submittedChunkIds = new ArrayList<>(), missingChunkIds = new ArrayList<>();
        private List<List<Integer>> observedRanges = new ArrayList<>();
        private int observedStartOffset, observedEndOffset, requestedIncrementalChars, budgetRemainingAtAdmission;
        private boolean initiallySubmitted, originSubmitted, observedRangesTransported;
        // The corpus has no independently bound original block length; do not infer it from the largest end offset.
        private boolean sourceBlockCompleteKnown = false;
    }
    private static final class Member {
        final Chunk chunk; final Part part; final boolean mapped;
        Member(Chunk c,Part p,boolean valid) {chunk=c;part=p;mapped=valid;}
    }
    public VettingBlockContinuation(List<Chunk> corpus) {
        for(Chunk c:corpus) {
            if(c.getParts()==null) continue;
            boolean mapped = c.getParts().stream().allMatch(Objects::nonNull)
                    && c.getParts().stream().map(Part::getText).allMatch(Objects::nonNull)
                    && Objects.equals(c.getContent(),c.getParts().stream().map(Part::getText).collect(Collectors.joining("\n")));
            Set<String> duplicate = new HashSet<>();
            for(Part p:c.getParts()) if(p!=null&&!nvl(p.getBlockId()).isEmpty()) {
                List<String> key=key(c,p);
                boolean unique=duplicate.add(p.getBlockId()+"|"+p.getStartOffset()+"|"+p.getEndOffset());
                members.computeIfAbsent(key,k->new ArrayList<>()).add(new Member(c,p,mapped&&unique));
            }
        }
    }

    public List<Trace> resolve(Chunk origin) {
        List<Trace> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        if(origin.getParts()==null)return out;
        for(Part p:origin.getParts()) {
            if(p==null||nvl(p.getBlockId()).isEmpty()||!seen.add(p.getBlockId()))continue;
            List<Member> group=members.getOrDefault(key(origin,p),Collections.emptyList());
            boolean split=p.getStartOffset()>0||OFFSET.matcher(nvl(p.getAnchor())).matches()
                    ||group.stream().anyMatch(m->m.part.getStartOffset()!=p.getStartOffset()||m.part.getEndOffset()!=p.getEndOffset());
            if(!split)continue;
            Trace t=new Trace();t.setOriginId(origin.getId());t.setSourceIdentity(source(origin));t.setBlockId(p.getBlockId());t.setSourceAnchor(stem(p));out.add(t);
            if(nvl(origin.getDocumentId()).isEmpty()||nvl(origin.getSourceHash()).isEmpty()||nvl(origin.getRole()).isEmpty()) {t.setStatus("unknown_source_identity");continue;}
            if(group.isEmpty()||group.stream().anyMatch(m->!m.mapped||!valid(m.part))) {t.setStatus("invalid_source_mapping");continue;}
            if(group.stream().anyMatch(m->!Objects.equals(stem(m.part),stem(p))||!sameVersions(origin,m.chunk))) {t.setStatus("ambiguous_source_block");continue;}
            List<Member> sorted=new ArrayList<>(group);sorted.sort(Comparator.comparingInt((Member m)->m.part.getStartOffset()).thenComparingInt(m->m.part.getEndOffset()));
            StringBuilder text=new StringBuilder();LinkedHashSet<String> ids=new LinkedHashSet<>();Set<String> ranges=new HashSet<>();boolean valid=true;
            for(Member m:sorted) {
                Part slice=m.part;int start=slice.getStartOffset(),end=slice.getEndOffset();
                if(start>text.length()) {t.setStatus(start>0&&text.length()==0?"missing_observed_block_start":"observed_range_gap");valid=false;break;}
                int overlap=Math.min(end,text.length())-start;
                if(overlap>0&&!text.substring(start,start+overlap).equals(slice.getText().substring(0,overlap))) {t.setStatus("inconsistent_source_overlap");valid=false;break;}
                if(end>text.length())text.append(slice.getText().substring(overlap));
                ids.add(m.chunk.getId());
                if(ranges.add(start+":"+end))t.getObservedRanges().add(Arrays.asList(start,end));
            }
            if(!valid)continue;
            t.setObservedStartOffset(0);t.setObservedEndOffset(text.length());
            t.setRequiredChunkIds(new ArrayList<>(ids));t.setStatus("observed_ranges_located");
        }
        return out;
    }
    private static boolean valid(Part p) {
        if(p.getText()==null||nvl(p.getAnchor()).isEmpty()||p.getStartOffset()<0||p.getEndOffset()<=p.getStartOffset()
                ||p.getEndOffset()-p.getStartOffset()!=p.getText().length())return false;
        Matcher m=OFFSET.matcher(p.getAnchor());
        if(p.getStartOffset()>0&&!m.matches())return false;
        if(m.matches())try {if(Integer.parseInt(m.group(2))!=p.getStartOffset())return false;}catch(NumberFormatException e){return false;}
        return !Character.isLowSurrogate(p.getText().charAt(0))&&!Character.isHighSurrogate(p.getText().charAt(p.getText().length()-1));
    }
    private static boolean sameVersions(Chunk a,Chunk b) {return Objects.equals(a.getMetadataVersion(),b.getMetadataVersion())
            &&Objects.equals(a.getSegmentationVersion(),b.getSegmentationVersion())&&Objects.equals(a.getNativeTableMetadataVersion(),b.getNativeTableMetadataVersion())
            &&Objects.equals(a.getSourceQualityMetadataVersion(),b.getSourceQualityMetadataVersion())&&Objects.equals(a.getSourceQualityHash(),b.getSourceQualityHash());}
    private static String stem(Part p) {Matcher m=OFFSET.matcher(nvl(p.getAnchor()));return m.matches()?m.group(1):nvl(p.getAnchor());}
    private static List<String> key(Chunk c,Part p) {return Arrays.asList(nvl(c.getDocumentId()),nvl(c.getSourceHash()),nvl(c.getRole()),p.getBlockId());}
    private static String source(Chunk c) {return nvl(c.getDocumentId())+"|"+nvl(c.getSourceHash())+"|"+nvl(c.getRole());}
    private static String nvl(String s) {return s==null?"":s;}
}
