package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.stream.Collectors;

/** Source-only attached retrieval episode; members never join the ranked List. */
final class VettingRetrievalSourceUnits {
    static final String POLICY="unique-source-structural-unit-seeds-v1";
    static final List<String> FIELDS=Arrays.asList("documentId","sourceHash","role","metadataVersion","segmentationVersion","nativeTableMetadataVersion","sourceQualityMetadataVersion","sourceQualityHash");
    static final class Unit {
        final String originId,status,reason;
        final List<String> ids;
        final Map<String,Object> trace;
        Unit(String origin,String status,String reason,List<String> ids,Map<String,Object> trace){this.originId=origin;this.status=status;this.reason=reason;this.ids=Collections.unmodifiableList(ids);this.trace=Collections.unmodifiableMap(trace);}
    }
    static final class RankedChunks extends ArrayList<Chunk> {
        final List<Unit> units;
        RankedChunks(List<Chunk> ranked,List<Unit> units){super(ranked);this.units=Collections.unmodifiableList(units);}
    }
    static List<Unit> attached(List<Chunk> ranked){return ranked instanceof RankedChunks?((RankedChunks)ranked).units:Collections.emptyList();}
    static List<Chunk> read(JsonNode root,List<Chunk> ranked,Map<String,Chunk> corpus,String role,boolean required) {
        JsonNode episode=root.get("sourceUnits");
        if(episode==null){require(!required,"advertised source-unit episode is missing");return ranked;}
        require(episode.isObject()&&POLICY.equals(episode.path("policy").asText())&&episode.path("units").isArray(),"source-unit episode shape/policy differs");
        List<String> rankedIds=ranked.stream().map(Chunk::getId).collect(Collectors.toList());
        require(textIds(episode.path("originalHitIds")).equals(rankedIds)&&episode.path("units").size()==ranked.size(),"source-unit original seed identities differ");
        require(episode.path("derivedMembersCanBecomeOrigins").isBoolean()&&!episode.path("derivedMembersCanBecomeOrigins").asBoolean(),"derived source units cannot become origins");
        List<Unit> units=new ArrayList<>();Set<String> seenUnits=new HashSet<>(),seenStructuralKeys=new HashSet<>();int previousOrdinal=0;
        for(int i=0;i<ranked.size();i++) {
            Chunk seed=ranked.get(i);JsonNode u=episode.path("units").get(i);
            require(u.isObject()&&textIds(u.path("originIds")).equals(Collections.singletonList(seed.getId()))&&u.path("unitId").asText().matches("[a-f0-9]{64}")&&seenUnits.add(u.path("unitId").asText()),"source-unit ranked origin or unique unit identity differs");
            require(u.path("derivedMembersCanBecomeOrigins").isBoolean()&&!u.path("derivedMembersCanBecomeOrigins").asBoolean()&&u.path("memberScores").isNull(),"unranked source members cannot receive scores or become origins");
            require(u.path("sourceIdentity").equals(identity(seed)),"source-unit identity differs from original ranked seed");
            JsonNode hit=root.path("hits").get(i);
            require(u.path("originalRerankOrdinal").isIntegralNumber()&&u.path("originalRerankOrdinal").canConvertToInt()&&u.path("originalRerankOrdinal").asInt()>previousOrdinal&&u.path("seedScore").equals(hit.path("score")),"source-unit seed rank/score differs");previousOrdinal=u.path("originalRerankOrdinal").asInt();
            String status=u.path("status").asText(),reason=null;List<String> ids=textIds(u.path("requiredMemberIds"));
            if("complete_observed_unit".equals(status)) {
                List<String> expected=members(seed,u,corpus);
                require(seenStructuralKeys.add(JsonUtils.write(Arrays.asList(u.path("sourceIdentity"),u.path("kind"),u.path("clauseId"),u.path("headingLocation"),u.path("blockIds"),expected))),"two ranked seeds claim the same reliable structure unit");
                require(ids.equals(expected)&&u.path("members").isArray()&&u.path("members").size()==ids.size(),"source-unit complete member set differs");
                for(int j=0;j<ids.size();j++)require(VettingRetrievalClient.normalizedPayload(corpus.get(ids.get(j))).equals(u.path("members").get(j)),"source-unit original member payload differs");
                require(u.path("reason").isNull(),"known source-unit has unknown reason");
            } else {
                require("unknown".equals(status)&&ids.isEmpty()&&u.path("members").isArray()&&u.path("members").isEmpty()&&u.path("reason").isTextual()&&!u.path("reason").asText().isEmpty(),"unknown source-unit must not transport guessed members");
                reason=u.path("reason").asText();
            }
            Map<String,Object> trace=JsonUtils.readMap(JsonUtils.write(u));trace.remove("members");
            units.add(new Unit(seed.getId(),status,reason,new ArrayList<>(ids),trace));
        }
        return new RankedChunks(ranked,units);
    }
    private static List<String> members(Chunk seed,JsonNode unit,Map<String,Chunk> corpus) {
        JsonNode scope=identity(seed);require(completeScope(seed),"source-unit identity/quality/version is unknown");
        List<Chunk> source=new ArrayList<>();
        for(Chunk c:corpus.values())if(Objects.equals(c.getDocumentId(),seed.getDocumentId())) {
            require(identity(c).equals(scope),"document has ambiguous source identity");
            require(JsonUtils.write(c.getSourceQuality()).equals(JsonUtils.write(seed.getSourceQuality())),"document source quality differs");source.add(c);
        }
        Set<String> ids=new TreeSet<>(),blocks=new TreeSet<>();String kind=unit.path("kind").asText();
        if("exact_clause_heading".equals(kind)) {
            require(!blank(seed.getClauseId())&&!blank(seed.getClauseHeadingLocation())&&Objects.equals(seed.getClauseId(),unit.path("clauseId").asText())&&Objects.equals(seed.getClauseHeadingLocation(),unit.path("headingLocation").asText()),"clause heading identity differs");
            List<Chunk> same=source.stream().filter(c->Objects.equals(c.getClauseId(),seed.getClauseId())).collect(Collectors.toList());
            require(same.stream().allMatch(c->Objects.equals(c.getClauseHeadingLocation(),seed.getClauseHeadingLocation())),"clause heading is not unique");
            boolean located=false;
            for(Chunk c:same){mapped(c);ids.add(c.getId());for(Part p:c.getParts()){blocks.add(p.getBlockId());String location=p.getAnchor().split(" @")[0];if(location.equals(seed.getClauseHeadingLocation())||location.endsWith(" · "+seed.getClauseHeadingLocation()))located=true;}}
            require(located,"clause heading is not located in observed Parts");
            for(Chunk c:source)if(c.getParts()!=null&&c.getParts().stream().anyMatch(p->blocks.contains(p.getBlockId())))require(ids.contains(c.getId()),"source block crosses clause unit boundary");
        } else {
            require("observed_source_blocks".equals(kind)&&blank(seed.getClauseId()),"source-unit kind differs");mapped(seed);
            for(Part p:seed.getParts())blocks.add(p.getBlockId());
            for(Chunk c:source)if(c.getParts()!=null&&c.getParts().stream().anyMatch(p->blocks.contains(p.getBlockId()))){mapped(c);ids.add(c.getId());}
        }
        require(textIds(unit.path("blockIds")).equals(new ArrayList<>(blocks)),"source-unit observed block set differs");
        require(unit.path("observedRanges").isArray()&&unit.path("observedRanges").size()==blocks.size(),"source-unit range trace differs");
        int index=0;
        for(String block:blocks) {
            List<Part> parts=new ArrayList<>();for(Chunk c:source)if(c.getParts()!=null)for(Part p:c.getParts())if(block.equals(p.getBlockId()))parts.add(p);
            parts.sort(Comparator.comparingInt(Part::getStartOffset).thenComparingInt(Part::getEndOffset));int end=0;
            for(int a=0;a<parts.size();a++) {
                Part p=parts.get(a);require(p.getStartOffset()<=end,"observed source block has missing start or gap");
                for(int b=0;b<a;b++){Part other=parts.get(b);int left=Math.max(p.getStartOffset(),other.getStartOffset()),right=Math.min(p.getEndOffset(),other.getEndOffset());if(left<right)require(p.getText().substring(left-p.getStartOffset(),right-p.getStartOffset()).equals(other.getText().substring(left-other.getStartOffset(),right-other.getStartOffset())),"overlapping source block text differs");}
                end=Math.max(end,p.getEndOffset());
            }
            JsonNode range=unit.path("observedRanges").get(index++);require(block.equals(range.path("blockId").asText())&&range.path("startUtf16").isIntegralNumber()&&range.path("startUtf16").asInt()==0&&range.path("endUtf16").isIntegralNumber()&&range.path("endUtf16").asInt()==end&&"observed_canonical_ranges_zero_gap".equals(range.path("status").asText())&&range.path("sourceBlockComplete").isNull(),"source-unit observed range boundary differs");
        }
        require(ids.contains(seed.getId()),"source-unit lost its ranked seed");return new ArrayList<>(ids);
    }
    private static void mapped(Chunk c) {
        require(c.getParts()!=null&&!c.getParts().isEmpty(),"source-unit Parts absent");List<String> texts=new ArrayList<>();
        for(Part p:c.getParts()){require(p!=null&&!blank(p.getBlockId())&&!blank(p.getAnchor())&&p.getText()!=null&&p.getStartOffset()>=0&&p.getEndOffset()-p.getStartOffset()==p.getText().length(),"source-unit Part UTF16 identity differs");texts.add(p.getText());}
        String joined=String.join("\n",texts);require(Objects.equals(c.getContent(),joined)||Objects.equals(c.getContent(),joined.trim()),"source-unit Parts/content mapping differs");
    }
    private static boolean completeScope(Chunk c){return FIELDS.stream().allMatch(k->identity(c).path(k).isTextual()&&!blank(identity(c).path(k).asText()))&&c.getSourceHash().matches("[a-f0-9]{64}")&&c.getSourceQualityHash().matches("[a-f0-9]{64}")&&c.getSourceQuality()!=null;}
    private static JsonNode identity(Chunk c){JsonNode raw=JsonUtils.mapper().valueToTree(c);com.fasterxml.jackson.databind.node.ObjectNode o=JsonUtils.mapper().createObjectNode();for(String key:FIELDS)o.set(key,raw.path(key));return o;}
    static List<String> textIds(JsonNode value){require(value.isArray(),"source-unit ID array absent");List<String> out=new ArrayList<>();for(JsonNode id:value){require(id.isTextual()&&!blank(id.asText())&&!out.contains(id.asText()),"source-unit missing/duplicate ID");out.add(id.asText());}return out;}
    private static boolean blank(String s){return s==null||s.trim().isEmpty();}
    private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException("Required hybrid structural episode rejected: "+why);}
}
