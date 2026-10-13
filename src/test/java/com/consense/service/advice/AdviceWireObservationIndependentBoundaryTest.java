package com.consense.service.advice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent negative fixtures. Only the declared synthetic key is used. */
class AdviceWireObservationIndependentBoundaryTest {
    @TempDir Path root;
    String escapedSecret() {
        StringBuilder value=new StringBuilder();
        for(char c:AdviceResponsesTransportTest.SECRET.toCharArray())value.append(String.format("\\u%04x",(int)c));
        return value.toString();
    }
    AdviceResponsesTransport.RequestFailure exercise(String name,String entity) throws Exception {
        AdviceResponsesTransportTest.Fixture f=new AdviceResponsesTransportTest.Fixture();
        AdviceResponsesTransport.Operation operation=new AdviceResponsesTransport(f.props,f.settings,f.http,
            new AdviceWireObservation(root.toAbsolutePath())).capture(f.selection);
        AdviceResponsesTransport.PreparedRequest request=operation.prepare("safe system","safe source",
            AdviceResponsesTransportTest.SOURCE,0);
        f.http.replies.add(new AdviceResponsesTransport.Response(529,entity));
        AdviceResponsesTransport.RequestFailure failure=assertThrows(AdviceResponsesTransport.RequestFailure.class,
            ()->operation.observe(request,()->{}));
        Path attempt;try(java.util.stream.Stream<Path> stream=Files.list(root)){attempt=stream.findFirst().orElseThrow();}
        Path evidence=root.resolve("probe-evidence").resolve(name);
        Files.createDirectories(evidence);
        Files.write(evidence.resolve("synthetic-input.utf8.json"),entity.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        Path captured=attempt.resolve("counter-response.utf8.json");
        if(Files.exists(captured))Files.copy(captured,evidence.resolve("persisted-raw.utf8.json"));
        String diagnostic="syntheticOnly=true\nhttpCalls="+f.http.bodies.size()+"\nrawPersisted="+Files.exists(captured)+
            "\nsafeFailureStatus="+failure.getStatus()+"\nsafeFailureReason="+failure.getReason()+"\n";
        Files.write(evidence.resolve("probe.txt"),diagnostic.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        assertEquals(1,f.http.bodies.size());
        assertEquals("credential_reflection_refused_no_raw_body_saved",failure.getReason(),
            "Known Unicode-escaped credential must be rejected before raw persistence, even in unaccepted envelopes");
        assertFalse(Files.exists(captured),"No exact body containing the known synthetic credential may be persisted");
        return failure;
    }
    @Test void duplicateFieldCannotHideEscapedCredentialFromPrePersistenceCheck() throws Exception {
        exercise("duplicate-field","{\"message\":\""+escapedSecret()+"\",\"message\":\"safe overwritten value\"}");
    }
    @Test void trailingJsonValueCannotHideEscapedCredentialFromPrePersistenceCheck() throws Exception {
        exercise("trailing-json","{\"message\":\"safe first object\"}\n{\"message\":\""+escapedSecret()+"\"}");
    }
    @Test void duplicateUsageRemainsUnknownWithoutInferredCounts() throws Exception {
        AdviceResponsesTransportTest.Fixture f=new AdviceResponsesTransportTest.Fixture();
        AdviceResponsesTransport.PreparedRequest request=f.operation().prepare("safe","safe",AdviceResponsesTransportTest.SOURCE,0);
        AdviceWireObservation.Attempt attempt=new AdviceWireObservation(root.toAbsolutePath())
            .begin(request,Collections.emptyMap(),AdviceResponsesTransportTest.SECRET,false);
        String entity="{\"usage\":{\"input_tokens\":3,\"output_tokens\":2},\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}";
        attempt.response("generation","https://api.minimax.cn/v1/responses",new AdviceResponsesTransport.Response(200,entity),1);
        com.fasterxml.jackson.databind.JsonNode receipt=com.consense.common.JsonUtils.parse(
            new String(Files.readAllBytes(attempt.directory.resolve("generation-response-receipt.json")),StandardCharsets.UTF_8));
        assertEquals("unknown",receipt.path("usageObservation").path("kind").asText(),
            "Duplicate usage envelope must remain unknown rather than selecting a count projection");
    }
}
