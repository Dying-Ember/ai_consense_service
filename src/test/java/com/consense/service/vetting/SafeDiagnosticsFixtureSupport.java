package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.*;
import java.lang.reflect.*;
import java.net.UnknownHostException;
import java.util.*;

/** Synthetic values only; no network, credential, evaluator answer or corpus fixture. */
public final class SafeDiagnosticsFixtureSupport {
    private SafeDiagnosticsFixtureSupport() { }
    static VettingResponsesTransport.TypedTransportException typed(String phase,Integer status,long bytes) {
        try {
            VettingResponsesTransport.CallState state=new VettingResponsesTransport.CallState();
            state.phase=phase;state.responseStatus=status;state.bytesRead=bytes;
            Constructor<VettingResponsesTransport.TypedTransportException> constructor=VettingResponsesTransport.TypedTransportException.class.getDeclaredConstructor(VettingResponsesTransport.CallState.class,Exception.class);
            constructor.setAccessible(true);
            return constructor.newInstance(state,new UnknownHostException("SYNTHETIC_PRIVATE_ORIGINAL_MESSAGE"));
        } catch(Exception e) {throw new AssertionError(e);}
    }
    static VettingInputBudget.Input input() {
        return VettingInputBudget.input("synthetic","SYNTHETIC_MODEL","Synthetic system","Source | East 中文 😀",JsonUtils.parse("{\"type\":\"array\"}"),64,VettingCorpus.hash("synthetic-snapshot"));
    }
    static VettingInputBudget.Observation observed(VettingInputBudget.Input in) {
        VettingInputBudget.Observation o=new VettingInputBudget.Observation();o.setModel(in.getModel());o.setSerializedInputSha256(in.getSerializedInputSha256());
        o.setTokenizerIdentitySha256(VettingCorpus.hash("synthetic-tokenizer"));o.setChatTemplateIdentitySha256(VettingCorpus.hash("synthetic-template"));o.setEffectiveContextIdentitySha256(VettingCorpus.hash("synthetic-context"));
        o.setInputTokens(100);o.setEffectiveContextTokens(4096);o.setOutputReserveTokens(in.getOutputReserveTokens());o.setCompleteChatTemplateAndSchemaObserved(true);return o;
    }
    static Map<String,Object> legacyResults() {
        VettingInputBudget.Input in=input();Map<String,Object> out=new LinkedHashMap<>();
        out.put("noObserver",VettingInputBudget.check(in,null));
        out.put("ordinaryIllegalState",VettingInputBudget.check(in,x->{throw new IllegalStateException("SYNTHETIC_PRIVATE_ORIGINAL_MESSAGE");}));
        out.put("nestedTypedCause",VettingInputBudget.check(in,x->{throw new IllegalStateException("SYNTHETIC_PRIVATE_ORIGINAL_MESSAGE",typed("dns",null,0));}));
        out.put("ordinaryIllegalArgument",VettingInputBudget.check(in,x->{throw new IllegalArgumentException("SYNTHETIC_PRIVATE_ORIGINAL_MESSAGE");}));
        out.put("nullObservation",VettingInputBudget.check(in,x->null));
        out.put("fitObservation",VettingInputBudget.check(in,SafeDiagnosticsFixtureSupport::observed));
        out.put("overObservation",VettingInputBudget.check(in,x->{VettingInputBudget.Observation o=observed(x);o.setInputTokens(5000);return o;}));
        ConsenseProperties props=new ConsenseProperties();props.getVetting().getResponses().setEnabled(true);props.getVetting().getResponses().setConfiguredContextTokens(40000);props.getVetting().getResponses().setSafetyMarginTokens(512);props.getVetting().getResponses().setEstimatePolicy(VettingResponsesTransport.POLICY);props.getVetting().getResponses().setApiKey("SYNTHETIC_NOT_A_CREDENTIAL");
        for(String name:Arrays.asList("providerFit","providerOver","providerHttpFailure")) {
            VettingResponsesTransport transport=new VettingResponsesTransport(props,(endpoint,body,timeout,cap,auth)->new VettingResponsesTransport.Response("providerHttpFailure".equals(name)?403:200,"{\"object\":\"response.input_tokens\",\"input_tokens\":"+("providerOver".equals(name)?50000:100)+"}"));
            VettingInputBudget.Input remote=transport.input("Synthetic system","Synthetic source",JsonUtils.parse("{\"type\":\"array\"}"),VettingCorpus.hash("synthetic-snapshot"));out.put(name,VettingInputBudget.check(remote,transport::observe));
        }
        return out;
    }
    static Chunk chunk(String id,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId("synthetic-document");c.setFileName("synthetic.docx");c.setRole("tender");c.setSourceHash(VettingCorpus.hash("synthetic-original"));c.setFileKey("ABC");c.setClauseId("ABC.1");c.setClauseHeadingLocation("body/heading/1");c.setAnchor("body/"+id+"/paragraph");c.setContent(text);
        c.setMetadataVersion(VettingCorpus.METADATA_VERSION);c.setSegmentationVersion(VettingCorpus.SEGMENTATION_VERSION);c.setNativeTableMetadataVersion(VettingCorpus.NATIVE_TABLE_METADATA_VERSION);
        VettingSourceQuality.Info q=new VettingSourceQuality.Info();q.setParseStatus("PARTIAL");q.setOcrQualityStatus("needs_review");q.setCoverageMetadataSha256(VettingCorpus.hash("synthetic-source-declaration"));c.setSourceQuality(q);c.setSourceQualityHash(VettingSourceQuality.hash(q));c.setSourceQualityMetadataVersion(VettingSourceQuality.VERSION);
        Part p=new Part();p.setBlockId(id);p.setAnchor(c.getAnchor());p.setStartOffset(0);p.setEndOffset(text.length());p.setText(text);p.setExtractionSource("word");c.setParts(Collections.singletonList(p));return c;
    }
    public static void main(String[] args) {System.out.print(JsonUtils.write(legacyResults()));}
}
