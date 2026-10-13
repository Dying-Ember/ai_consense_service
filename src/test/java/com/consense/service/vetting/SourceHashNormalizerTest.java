package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceHashNormalizerTest {
    private static String legacy(String text,String structure) {
        text=text==null?"":text;structure=structure==null?"":structure;
        if(structure.contains("\"paragraphStyleName\"")) {
            JsonNode tree=JsonUtils.parse(structure);boolean enriched=false;
            if(tree.isArray())for(JsonNode block:tree)if(block.isObject()&&block.has("paragraphStyleName")) {
                ((ObjectNode)block).remove("paragraphStyleName");enriched=true;
            }
            if(enriched)structure=JsonUtils.write(tree);
        }
        return VettingCorpus.hash(text+"\n"+structure);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,String> memo(SourceHashNormalizer normalizer)throws Exception {
        Field f=SourceHashNormalizer.class.getDeclaredField("memo");f.setAccessible(true);
        return (Map<String,String>)f.get(normalizer);
    }
    private static SourceDocument source(String text,String structure) {
        SourceDocument d=new SourceDocument();d.setTextContent(text);d.setStructuredContentJson(structure);return d;
    }
    private static String marked(String value) {
        return "[{\"text\":\""+value+"\",\"paragraphStyleName\":\"Heading 1\",\"unknown\":{\"x\":true}}]";
    }
    @Test void corpusNullEmptyUnmarkedAndWhitespaceBytesRemainLegacyEquivalent() {
        String[][] cases={{null,null},{"",""},{" 中文😀\n","invalid unmarked JSON"},
            {"x"," [ { \"text\" : \"value\" } ] "},{"x","[{\"paragraph\\u0053tyleName\":\"Heading\"}]"}};
        for(String[] pair:cases)assertEquals(legacy(pair[0],pair[1]),VettingCorpus.sourceHash(source(pair[0],pair[1])));
    }
    @Test void onlyTopLevelArrayStyleFieldsAreRemovedAndUnknownFieldsStayLiteral() {
        SourceHashNormalizer n=new SourceHashNormalizer(4);
        String[] structures={marked("native")," { \"paragraphStyleName\": \"top object\" } ",
            "\"paragraphStyleName\"","[{\"nested\":{\"paragraphStyleName\":\"keep\"}}]",
            "[{\"text\":\"first\",\"text\":\"last\",\"paragraphStyleName\":null,\"nested\":{\"paragraphStyleName\":\"keep\"}},7,null]",
            "[{\"paragraphStyleName\":\"A\"},{\"paragraphStyleName\":\"B\",\"unknown\":[{},[]]}]"};
        for(String value:structures)for(int i=0;i<2;i++)assertEquals(legacy("native text",value),n.hash("native text",value));
    }
    @Test void styleOnlyEnrichmentPreservesRevisionButTextAndUnknownStructureChangesInvalidateIt() {
        SourceHashNormalizer n=new SourceHashNormalizer(4);
        String original=marked("first"),style=original.replace("Heading 1","Heading 2");
        assertEquals(n.hash("same",original),n.hash("same",style));
        assertNotEquals(n.hash("same",original),n.hash("diff",original));
        assertNotEquals(n.hash("same",original),n.hash("same",original.replace("\"x\":true","\"x\":false")));
        assertEquals(legacy("same",original),n.hash("same",original));
    }
    @Test void equalCombinedBytesWithDifferentFieldBoundariesDoNotReuseNormalization() {
        SourceHashNormalizer n=new SourceHashNormalizer(4);String array=marked("a");
        String first=n.hash("prefix"," \n"+array),second=n.hash("prefix\n ",array);
        assertEquals(VettingCorpus.hash("prefix\n \n"+array),SourceHashNormalizer.joinedHash("prefix\n ",array));
        assertEquals(legacy("prefix"," \n"+array),first);
        assertEquals(legacy("prefix\n ",array),second);assertNotEquals(first,second);
    }
    @Test void malformedMarkedJsonKeepsFailureClassAndMessageAndNeverMemoizes()throws Exception {
        SourceHashNormalizer n=new SourceHashNormalizer(4);
        String invalid="[{\"paragraphStyleName\":\"Heading\",";
        RuntimeException expected=assertThrows(RuntimeException.class,()->legacy("same",invalid));
        for(int i=0;i<2;i++) {
            RuntimeException actual=assertThrows(RuntimeException.class,()->n.hash("same",invalid));
            assertEquals(expected.getClass(),actual.getClass());assertEquals(expected.getMessage(),actual.getMessage());
        }
        assertTrue(memo(n).isEmpty());
    }
    @Test void malformedUtf16KeepsReplacementAndErrorSemanticsWithoutMemo()throws Exception {
        SourceHashNormalizer n=new SourceHashNormalizer(4);
        String[] malformed={"\uD800","\uDC00","a\uD800b","\uD800\uD800\uDC00","\uDC00\uD800"};
        for(String value:malformed) {
            String structure=marked(value);assertEquals(legacy(value,structure),n.hash(value,structure));
            assertEquals(legacy(value,structure),n.hash(value,structure));
            assertEquals(VettingCorpus.hash(value+"\n"+structure),SourceHashNormalizer.joinedHash(value,structure));
        }
        assertTrue(memo(n).isEmpty());
    }
    @Test void boundedUtf8EncodingMatchesLegacyAcrossBufferAndSurrogateBoundaries() {
        StringBuilder base=new StringBuilder();for(int i=0;i<8500;i++)base.append('a');
        String longText=base+"😀中文\uD800tail";
        for(int boundary:new int[]{0,1,8190,8191,8192,8193,8500}) {
            String text=longText.substring(0,boundary)+"\uD800";
            String structure="\uDC00"+longText.substring(boundary);
            assertEquals(VettingCorpus.hash(text+"\n"+structure),SourceHashNormalizer.joinedHash(text,structure));
        }
        Random random=new Random(71010);
        for(int i=0;i<500;i++) {
            StringBuilder text=new StringBuilder(),structure=new StringBuilder();
            for(int j=0;j<100;j++){text.append((char)random.nextInt(65536));structure.append((char)random.nextInt(65536));}
            assertEquals(VettingCorpus.hash(text+"\n"+structure),SourceHashNormalizer.joinedHash(text.toString(),structure.toString()));
        }
    }
    @Test void boundedLruEvictionRetainsOnlyTinyKeysAndDigestValues()throws Exception {
        SourceHashNormalizer n=new SourceHashNormalizer(3);String first=marked("first");
        assertEquals(legacy("first",first),n.hash("first",first));
        for(int i=0;i<10;i++)n.hash("entry"+i,marked("entry"+i));
        Map<String,String> values=memo(n);assertEquals(3,values.size());
        for(Map.Entry<String,String> value:values.entrySet()) {
            assertTrue(value.getKey().matches("[a-f0-9]{64}:[0-9]+:[0-9]+"));
            assertTrue(value.getKey().length()<100);assertTrue(value.getValue().matches("[a-f0-9]{64}"));
        }
        assertEquals(legacy("first",first),n.hash("first",first));assertEquals(3,values.size());
        assertThrows(IllegalArgumentException.class,()->new SourceHashNormalizer(0));
    }
    @Test void simultaneousSameAndDifferentInputsRemainDeterministicAndBounded()throws Exception {
        SourceHashNormalizer n=new SourceHashNormalizer(5);ExecutorService pool=Executors.newFixedThreadPool(8);
        CountDownLatch start=new CountDownLatch(1);List<Future<Void>> tasks=new ArrayList<>();
        try {
            for(int worker=0;worker<8;worker++){final int id=worker;tasks.add(pool.submit(()->{
                start.await();for(int i=0;i<40;i++) {
                    String text="thread "+(i%3==0?0:id),structure=marked("entry "+(i%7));
                    assertEquals(legacy(text,structure),n.hash(text,structure));
                }return null;}));}
            start.countDown();for(Future<Void> task:tasks)task.get(30,TimeUnit.SECONDS);
            assertTrue(memo(n).size()<=5);
        }finally{pool.shutdownNow();}
    }
}
