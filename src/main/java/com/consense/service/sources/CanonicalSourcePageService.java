package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Optional reviewed original-page sidecar. Canonical/model-input assetRefs stay unchanged. */
@Service @RequiredArgsConstructor
public class CanonicalSourcePageService {
    private final CanonicalEvidenceService evidence;
    private final CanonicalEvidenceBackend backend;
    private final CanonicalEvidenceProperties properties;
    private static void require(boolean ok,String reason){if(!ok)throw new CanonicalEvidenceException(HttpStatus.CONFLICT,reason);}
    public EvidencePointer pointer(String project,Long document,String projection,String unit,String module){
        return enrich(evidence.pointer(project,document,projection,unit,module));
    }
    /** Internal consumers pass only a pointer just verified by CanonicalEvidenceService. */
    public EvidencePointer enrich(EvidencePointer pointer){
        String expected=properties.getSourcePageRenderEvidenceIdentities().get(pointer.getProjectionSha256());
        if(expected==null)return pointer;
        require("consense.canonical-projection.v1".equals(pointer.getProjectionSchema())
            &&"normalized_xyxy_original_page".equals(pointer.getBboxConvention())
            &&pointer.getPhysicalPages()!=null&&!pointer.getPhysicalPages().isEmpty(),"CANONICAL_SOURCE_PAGE_CONTRACT_REQUIRED");
        JsonNode data=metadata(pointer,expected);
        pointer.setSourcePageRenderRefs(data.path("sourcePageRenderRefs").deepCopy());
        pointer.setSourceRenderEvidenceIdentitySha256(expected);
        pointer.setSourceRenderCaptureCompleteness(data.path("captureCompleteness").deepCopy());
        pointer.setSourceRenderLimitations(data.path("limitations").deepCopy());
        return pointer;
    }
    private JsonNode metadata(EvidencePointer pointer,String expected){
        require(expected.matches("[a-f0-9]{64}"),"CANONICAL_SOURCE_RENDER_IDENTITY_INVALID");
        JsonNode data=JsonUtils.parse(backend.sourcePages(pointer.getProjectionSha256(),pointer.getOriginalSourceSha256(),expected,null,pointer.getModule()).json());
        require("consense.source-page-render-evidence.v1".equals(data.path("schema").asText())
            &&pointer.getProjectionSha256().equals(data.path("projectionSha256").asText())
            &&pointer.getOriginalSourceSha256().equals(data.path("sourceSha256").asText())
            &&pointer.getSourceRevisionSha256().equals(data.path("sourceRevisionSha256").asText())
            &&expected.equals(data.path("combinedEvidenceIdentitySha256").asText())&&pointer.getUnitId().equals(data.path("unitId").asText()),"CANONICAL_SOURCE_RENDER_IDENTITY_MISMATCH");
        require(data.path("qualityAccepted").isBoolean()&&!data.path("qualityAccepted").asBoolean()
            &&data.path("wholeSourceAccepted").isBoolean()&&!data.path("wholeSourceAccepted").asBoolean()
            &&pointer.getRelations().equals(data.path("relations"))&&pointer.getUnknowns().equals(data.path("unknowns")),"CANONICAL_SOURCE_RENDER_UNKNOWN_OR_RELATION_MISMATCH");
        Set<Integer> wanted=new TreeSet<>(pointer.getPhysicalPages()),observed=new TreeSet<>();
        Set<String> seen=new HashSet<>();Map<String,JsonNode> parts=new HashMap<>();
        for(JsonNode part:pointer.getParts())parts.put(part.path("blockId").asText(),part);
        JsonNode refs=data.path("sourcePageRenderRefs");require(refs.isArray()&&refs.size()==wanted.size(),"CANONICAL_SOURCE_RENDER_INCOMPLETE_GROUP");
        for(JsonNode ref:refs){
            String id=ref.path("assetId").asText();int page=ref.path("physicalPageNo").asInt(-1);
            require("source_page_render".equals(ref.path("kind").asText())&&id.matches("source-render:[a-f0-9]{64}")&&seen.add(id)
                &&pointer.getUnitId().equals(ref.path("unitId").asText())&&pointer.getOriginalSourceSha256().equals(ref.path("sourceSha256").asText())
                &&pointer.getSourceRevisionSha256().equals(ref.path("sourceRevisionSha256").asText())&&pointer.getProjectionSha256().equals(ref.path("projectionSha256").asText())
                &&expected.equals(ref.path("combinedEvidenceIdentitySha256").asText())
                &&ref.path("physicalPageNo").isIntegralNumber()&&ref.path("physicalPageNo").asLong()>0
                &&ref.path("physicalPageNo").asLong()<=Integer.MAX_VALUE&&wanted.contains(page)&&observed.add(page)
                &&"image/png".equals(ref.path("mediaType").asText())&&ref.path("sha256").asText().matches("[a-f0-9]{64}")
                &&ref.path("physicalPages").isArray()&&ref.path("physicalPages").size()==1
                &&ref.path("physicalPages").get(0).isIntegralNumber()&&ref.path("physicalPages").get(0).asLong()==page
                &&ref.path("byteCount").isIntegralNumber()&&ref.path("byteCount").asLong()>0
                &&ref.path("widthPixels").isIntegralNumber()&&ref.path("widthPixels").asLong()>0&&ref.path("widthPixels").asLong()<=Integer.MAX_VALUE
                &&ref.path("heightPixels").isIntegralNumber()&&ref.path("heightPixels").asLong()>0&&ref.path("heightPixels").asLong()<=Integer.MAX_VALUE
                &&ref.path("humanConfirmed").isBoolean()&&!ref.path("humanConfirmed").asBoolean(),"CANONICAL_SOURCE_PAGE_REFERENCE_INVALID");
            Set<String> expectedBlocks=new HashSet<>();for(Map.Entry<String,JsonNode> part:parts.entrySet())if(part.getValue().path("physicalPageNo").asInt()==page)expectedBlocks.add(part.getKey());
            Set<String> declaredBlocks=new HashSet<>();for(JsonNode block:ref.path("blockIds"))require(declaredBlocks.add(block.asText()),"CANONICAL_SOURCE_PAGE_BLOCK_DUPLICATE");
            require(expectedBlocks.equals(declaredBlocks),"CANONICAL_SOURCE_PAGE_BLOCK_MEMBERSHIP_MISMATCH");
            Set<String> regions=new HashSet<>();for(JsonNode region:ref.path("sourceRegions")){
                String block=region.path("blockId").asText();JsonNode part=parts.get(block);
                require(part!=null&&declaredBlocks.contains(block)&&regions.add(block)&&part.path("bbox").equals(region.path("bbox"))
                    &&"normalized_xyxy_original_page".equals(region.path("bboxConvention").asText()),"CANONICAL_SOURCE_PAGE_REGION_MISMATCH");
            }
            require(regions.equals(expectedBlocks),"CANONICAL_SOURCE_PAGE_REGION_MEMBERSHIP_MISMATCH");
        }
        require(wanted.equals(observed),"CANONICAL_SOURCE_RENDER_INCOMPLETE_GROUP");
        return data;
    }
    public CanonicalEvidenceBackend.Reply asset(String project,Long document,String projection,String unit,String id,String expected,String module){
        require(expected.equals(properties.getSourcePageRenderEvidenceIdentities().get(projection)),"CANONICAL_SOURCE_RENDER_IDENTITY_MISMATCH");
        EvidencePointer pointer=pointer(project,document,projection,unit,module);JsonNode found=null;
        for(JsonNode ref:pointer.getSourcePageRenderRefs())if(id.equals(ref.path("assetId").asText())){require(found==null,"CANONICAL_SOURCE_PAGE_AMBIGUOUS");found=ref;}
        if(found==null)throw new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"CANONICAL_SOURCE_PAGE_NOT_BOUND");
        CanonicalEvidenceBackend.Reply reply=backend.sourcePages(projection,pointer.getOriginalSourceSha256(),expected,id,module);
        require(pointer.getOriginalSourceSha256().equals(reply.sourceSha256)&&projection.equals(reply.projectionSha256)
            &&pointer.getSourceRevisionSha256().equals(reply.sourceRevisionSha256)&&expected.equals(reply.evidenceIdentitySha256)
            &&"image/png".equals(reply.contentType)&&("\""+found.path("sha256").asText()+"\"").equals(reply.etag)
            &&reply.bytes.length==found.path("byteCount").asLong()&&CanonicalJson.sha(reply.bytes).equals(found.path("sha256").asText()),"CANONICAL_SOURCE_PAGE_BYTES_MISMATCH");
        try{BufferedImage image=ImageIO.read(new ByteArrayInputStream(reply.bytes));require(image!=null&&image.getWidth()==found.path("widthPixels").asInt()
            &&image.getHeight()==found.path("heightPixels").asInt(),"CANONICAL_SOURCE_PAGE_DIMENSIONS_MISMATCH");}
        catch(java.io.IOException invalid){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_SOURCE_PAGE_DECODE_FAILED");}
        EvidencePointer after=pointer(project,document,projection,unit,module);
        require(pointer.getSourcePageRenderRefs().equals(after.getSourcePageRenderRefs()),"CANONICAL_SOURCE_RENDER_CHANGED_DURING_READ");
        return reply;
    }
}
