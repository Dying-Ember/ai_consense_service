package com.consense.service.sources;
import com.consense.config.CanonicalEvidenceProperties;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Fixed route/cap before transport. No sockets or upstream requests. */
class CanonicalEnvelopeBackendContractTest {
    @Test void envelopeRoutePinsRootOrdinalModuleAndSixteenMiBCap(){
        CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();config.setBaseUrl("http://127.0.0.1:1");config.setMaxSnapshotBytes(Integer.MAX_VALUE);
        List<String> paths=new ArrayList<>();List<Integer> caps=new ArrayList<>();
        CanonicalEvidenceBackend backend=new CanonicalEvidenceBackend(config){@Override protected Reply request(HttpUrl url,int maximum){paths.add(url.encodedPath()+"?"+url.encodedQuery());caps.add(maximum);return new Reply(new byte[]{123,125},"application/json",null,null,null);}};
        String address=String.join("",Collections.nCopies(64,"a"));assertArrayEquals(new byte[]{123,125},backend.envelope(address,4,"advisory").bytes);
        assertEquals(Collections.singletonList("/canonical/"+address+"/envelopes/4?module=advisory"),paths);
        assertEquals(Collections.singletonList(16777216),caps);
        for(String invalid:Arrays.asList("../sha256/a.blob","https://other.test/a"))assertThrows(CanonicalEvidenceException.class,()->backend.envelope(invalid,0,"vetting"));
        assertThrows(CanonicalEvidenceException.class,()->backend.envelope(address,-1,"vetting"));
        assertThrows(CanonicalEvidenceException.class,()->backend.envelope(address,0,"vetting&module=drafting"));assertEquals(1,paths.size());
    }
    @Test void malformedUtf8CannotBecomeReplacementCharactersInJson(){
        CanonicalEvidenceBackend.Reply reply=new CanonicalEvidenceBackend.Reply(new byte[]{123,34,120,34,58,34,(byte)255,34,125},"application/json",null,null,null);
        assertThrows(CanonicalEvidenceException.class,reply::json);
    }
}
