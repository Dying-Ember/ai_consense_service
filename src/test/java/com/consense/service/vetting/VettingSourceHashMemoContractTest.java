package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VettingSourceHashMemoContractTest {
    static class CountingFactory extends JsonNodeFactory {
        final AtomicInteger objects=new AtomicInteger();
        @Override public ObjectNode objectNode(){objects.incrementAndGet();return super.objectNode();}
    }
    @Test void repeatedFreshSameBytesAvoidOnlySuccessfulNormalizationReparse() {
        JsonNodeFactory original=JsonUtils.mapper().getNodeFactory();CountingFactory counting=new CountingFactory();
        SourceDocument source=new SourceDocument();source.setTextContent("AUTHORED unique "+UUID.randomUUID());
        source.setStructuredContentJson("[{\"paragraphStyleName\":\"Heading 1\",\"text\":\"native block\",\"unknown\":{\"retained\":true}}]");
        try {
            JsonUtils.mapper().setNodeFactory(counting);String first=VettingCorpus.sourceHash(source);
            assertTrue(counting.objects.get()>0);counting.objects.set(0);
            assertEquals(first,VettingCorpus.sourceHash(source));
            assertEquals(0,counting.objects.get(),"Fresh same bytes should reuse only the deterministic normalization digest");
            source.setTextContent(source.getTextContent()+" changed");counting.objects.set(0);
            assertNotEquals(first,VettingCorpus.sourceHash(source));assertTrue(counting.objects.get()>0);
        }finally{JsonUtils.mapper().setNodeFactory(original);}
    }
}
