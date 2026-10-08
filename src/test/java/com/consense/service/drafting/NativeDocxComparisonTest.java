package com.consense.service.drafting;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import static org.junit.jupiter.api.Assertions.*;

class NativeDocxComparisonTest {
    private static final String NAME="CS0123456789abcdef0123456789ab";
    private static final String RUN="<w:r><w:rPr><w:b/></w:rPr><w:t>Exact source</w:t><w:tab/><w:br/></w:r>";
    private static String start(String id){return "<w:bookmarkStart w:id='"+id+"' w:name='"+NAME+"'/>";}
    private static String end(String id){return "<w:bookmarkEnd w:id='"+id+"'/>";}
    private static Document xml(String body)throws Exception{return NativeDocxComparison.parse(("<w:document xmlns:w='"+NativeDocxComparison.W+"'><w:body>"+body+"</w:body></w:document>").getBytes(StandardCharsets.UTF_8));}
    private static String paragraph(String body){return "<w:p><w:pPr><w:keepNext/></w:pPr>"+body+"</w:p>";}

    @Test void onlyCompleteUniqueNewDirectPairsAreIgnoredAndNativeDamageRemainsVisible()throws Exception {
        Document source=xml(paragraph(RUN));String marked=paragraph(start("7")+RUN+end("7"));
        Document supplied=xml(marked),before=(Document)supplied.cloneNode(true);
        assertTrue(source.isEqualNode(NativeDocxComparison.withoutAddedBindings(source,supplied)));
        assertTrue(before.isEqualNode(supplied),"Comparison does not mutate the exact supplied document");
        for(String damaged:new String[]{marked.replace("<w:b/>",""),marked.replace("<w:tab/>",""),marked.replace("<w:br/>",""),marked.replace("Exact source","Changed source")})
            assertFalse(source.isEqualNode(NativeDocxComparison.withoutAddedBindings(source,xml(damaged))),"Native text/properties/controls are never normalized away");
        assertEquals(1,NativeDocxComparison.elements(supplied,"bookmarkStart").size());
    }

    @Test void preexistingAndIncompleteOrAmbiguousMarkersRemainInExactComparison()throws Exception {
        Document source=xml(paragraph(RUN));
        for(String malformed:new String[]{paragraph(start("7")+RUN),paragraph(RUN+end("7")),paragraph(end("7")+RUN+start("7")),paragraph(start("7")+RUN)+paragraph(end("7")),paragraph(start("7")+start("7")+RUN+end("7")),paragraph(start("7")+RUN+end("7")+end("7")),paragraph(start("7")+RUN+end("7"))+paragraph(start("8")+RUN+end("8")),paragraph("<w:r>"+start("7")+"<w:t>Exact source</w:t>"+end("7")+"</w:r>")}) {
            Document output=xml(malformed);assertTrue(output.isEqualNode(NativeDocxComparison.withoutAddedBindings(source,output)),"Ambiguous markers must remain fully visible to native assertions");
        }
        Document preexisting=xml(paragraph(start("7")+RUN+end("7")));
        assertTrue(preexisting.isEqualNode(NativeDocxComparison.withoutAddedBindings(preexisting,preexisting)),"Source CS bookmarks remain exact");
        Document idCollision=xml(paragraph("<w:bookmarkStart w:id='7' w:name='Original'/><w:bookmarkEnd w:id='7'/>"+RUN));
        Document output=xml(paragraph(start("7")+RUN+end("7")));assertTrue(output.isEqualNode(NativeDocxComparison.withoutAddedBindings(idCollision,output)),"An original native ID cannot become ignorable");
    }

    @Test void markerChildrenAndUnexpectedNativeAttributesAreNeverNormalizedAway()throws Exception {
        Document source=xml(paragraph(RUN));
        String startChild=start("7").replace("/>","><w:r><w:t>Added content</w:t><w:br w:type='page'/></w:r></w:bookmarkStart>");
        String endChild=end("7").replace("/>","><w:r><w:tab/></w:r></w:bookmarkEnd>");
        for(String marked:new String[]{paragraph(startChild+RUN+end("7")),paragraph(start("7")+RUN+endChild),paragraph(start("7").replace("/>"," w:displacedByCustomXml='prev'/>")+RUN+end("7")),paragraph(start("7")+RUN+end("7").replace("/>"," w:unknown='retained'/>"))}) {
            Document output=xml(marked),normalized=NativeDocxComparison.withoutAddedBindings(source,output);
            assertTrue(output.isEqualNode(normalized),"Child content and unexpected native marker attributes remain visible");
            assertFalse(source.isEqualNode(normalized),"A malformed marker cannot conceal native content or controls");
        }
    }
}
