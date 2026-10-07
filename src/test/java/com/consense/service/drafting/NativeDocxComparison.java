package com.consense.service.drafting;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Exact native XML comparison, excluding only complete, uniquely identified added binding pairs. */
final class NativeDocxComparison {
    static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private NativeDocxComparison() {}

    static Document parse(byte[] documentXml)throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(documentXml));
    }

    static Document withoutAddedBindings(Document source,Document output) {
        Document normalized=(Document)output.cloneNode(true);
        Set<String> sourceIds=new HashSet<>(),sourceNames=new HashSet<>();
        for(Element marker:elements(source,"bookmarkStart")){sourceIds.add(marker.getAttributeNS(W,"id"));sourceNames.add(marker.getAttributeNS(W,"name"));}
        for(Element marker:elements(source,"bookmarkEnd"))sourceIds.add(marker.getAttributeNS(W,"id"));
        List<Element> starts=elements(normalized,"bookmarkStart"),ends=elements(normalized,"bookmarkEnd");
        Map<String,Integer> ids=new HashMap<>(),names=new HashMap<>();Map<String,List<Element>> endsById=new HashMap<>();
        for(Element start:starts){ids.merge(start.getAttributeNS(W,"id"),1,Integer::sum);names.merge(start.getAttributeNS(W,"name"),1,Integer::sum);}
        for(Element end:ends)endsById.computeIfAbsent(end.getAttributeNS(W,"id"),ignored->new ArrayList<>()).add(end);
        for(Element start:starts) {
            String id=start.getAttributeNS(W,"id"),name=start.getAttributeNS(W,"name");Node parent=start.getParentNode();
            List<Element> matchingEnds=endsById.getOrDefault(id,Collections.emptyList());
            if(!generatedLeaf(start,Set.of("id","name"))||!name.matches("CS[a-f0-9]{28}")||!id.matches("[0-9]+")||sourceIds.contains(id)||sourceNames.contains(name)
                    ||ids.get(id)!=1||names.get(name)!=1||matchingEnds.size()!=1
                    ||!W.equals(parent.getNamespaceURI())||!"p".equals(parent.getLocalName()))continue;
            Element end=matchingEnds.get(0);if(!generatedLeaf(end,Set.of("id"))||end.getParentNode()!=parent)continue;
            boolean ordered=false;for(Node sibling=start.getNextSibling();sibling!=null;sibling=sibling.getNextSibling())if(sibling==end){ordered=true;break;}
            if(ordered){parent.removeChild(start);parent.removeChild(end);}
        }
        return normalized;
    }

    private static boolean generatedLeaf(Element marker,Set<String> allowed) {
        if(marker.hasChildNodes())return false;
        NamedNodeMap attributes=marker.getAttributes();
        for(int i=0;i<attributes.getLength();i++) {
            Node attribute=attributes.item(i);
            if(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI()))continue;
            if(!W.equals(attribute.getNamespaceURI())||!allowed.contains(attribute.getLocalName()))return false;
        }
        return true;
    }

    static boolean retainsParagraph(byte[] source,int ordinal,byte[] emitted)throws Exception {
        Document before=mainDocument(source);
        Document after=withoutAddedBindings(before,mainDocument(emitted));
        Node expected=before.getElementsByTagNameNS(W,"p").item(ordinal-1);
        for(Element candidate:elements(after,"p"))if(expected.isEqualNode(candidate))return true;
        return false;
    }

    private static Document mainDocument(byte[] docx)throws Exception {
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;while((entry=zip.getNextEntry())!=null)if("word/document.xml".equals(entry.getName()))return parse(zip.readAllBytes());
        }
        throw new IOException("Missing native main document");
    }

    static List<Element> elements(Document document,String localName) {
        NodeList nodes=document.getElementsByTagNameNS(W,localName);List<Element> result=new ArrayList<>();
        for(int i=0;i<nodes.getLength();i++)result.add((Element)nodes.item(i));return result;
    }
}
