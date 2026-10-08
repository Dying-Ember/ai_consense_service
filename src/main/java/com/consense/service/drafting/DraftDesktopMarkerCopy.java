package com.consense.service.drafting;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Conversion-only links on existing safe runs. Never saves or changes the caller's DOCX. */
final class DraftDesktopMarkerCopy {
    static final String URI_PREFIX="consense-binding:";
    private final String uriNonce=UUID.randomUUID().toString().replace("-","");
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";
    final byte[] bytes;
    final boolean ambiguousSourceUris;
    final Set<String> registered=new LinkedHashSet<>(),linked=new LinkedHashSet<>(),unavailable=new LinkedHashSet<>();
    DraftDesktopMarkerCopy(byte[] source,long deadline)throws IOException {
        try {
            Map<String,byte[]> entries=new LinkedHashMap<>();long total=0;
            try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(source))) {
                ZipEntry entry;byte[] buffer=new byte[8192];
                while((entry=zip.getNextEntry())!=null) {
                    DraftPdfProcess.checkDeadline(deadline);
                    if(entries.size()>=5000||entries.containsKey(entry.getName()))throw new IOException("Duplicate/too many package entries.");
                    ByteArrayOutputStream out=new ByteArrayOutputStream();int n;
                    while((n=zip.read(buffer))!=-1){total+=n;if(out.size()+(long)n>128L*1024*1024||total>256L*1024*1024)throw new IOException("Inflated package limit.");out.write(buffer,0,n);DraftPdfProcess.checkDeadline(deadline);}
                    entries.put(entry.getName(),out.toByteArray());
                }
            }
            Document main=parse(entries.get("word/document.xml"));
            byte[] relationshipBytes=entries.get("word/_rels/document.xml.rels");
            Document relationships=relationshipBytes==null?parse(("<Relationships xmlns=\""+REL+"\"/>").getBytes(java.nio.charset.StandardCharsets.UTF_8)):parse(relationshipBytes);
            Set<String> relationshipIds=new HashSet<>();NodeList old=relationships.getElementsByTagNameNS(REL,"Relationship");boolean reservedUriPresent=false;
            for(int i=0;i<old.getLength();i++){Element e=(Element)old.item(i);relationshipIds.add(e.getAttribute("Id"));if(e.getAttribute("Target").toLowerCase(Locale.ROOT).startsWith(URI_PREFIX))reservedUriPresent=true;}
            ambiguousSourceUris=reservedUriPresent;
            Map<String,Integer> names=new HashMap<>(),ids=new HashMap<>(),endIds=new HashMap<>();
            NodeList starts=main.getElementsByTagNameNS(W,"bookmarkStart"),ends=main.getElementsByTagNameNS(W,"bookmarkEnd");
            for(int i=0;i<starts.getLength();i++){Element e=(Element)starts.item(i);count(names,e.getAttributeNS(W,"name"));count(ids,e.getAttributeNS(W,"id"));}
            for(int i=0;i<ends.getLength();i++)count(endIds,((Element)ends.item(i)).getAttributeNS(W,"id"));
            for(int i=0;i<starts.getLength();i++) {
                DraftPdfProcess.checkDeadline(deadline);Element start=(Element)starts.item(i);String name=start.getAttributeNS(W,"name"),id=start.getAttributeNS(W,"id");
                if(!name.matches("CS[0-9a-f]{28}"))continue;
                registered.add(name);
                unavailable.add(name);Element paragraph=start.getParentNode() instanceof Element?(Element)start.getParentNode():null;
                // Existing reserved links make observed IDs ambiguous; strip them after rendering,
                // but never treat their coordinates as links introduced by this conversion.
                if(reservedUriPresent)continue;
                if(names.get(name)!=1||ids.get(id)!=1||endIds.getOrDefault(id,0)!=1||!is(paragraph,"p"))continue;
                boolean ended=false;for(Node n=start.getNextSibling();n!=null;n=n.getNextSibling())if(is(n,"bookmarkEnd")&&id.equals(((Element)n).getAttributeNS(W,"id"))){ended=true;break;}
                if(!ended)continue;
                Element run=null;boolean field=false;
                for(Node n=start.getNextSibling();n!=null;n=n.getNextSibling()) {
                    if(is(n,"bookmarkEnd")&&id.equals(((Element)n).getAttributeNS(W,"id")))break;
                    if(!is(n,"r"))continue;Element candidate=(Element)n;
                    if(candidate.getElementsByTagNameNS(W,"fldChar").getLength()>0||candidate.getElementsByTagNameNS(W,"instrText").getLength()>0){field=true;break;}
                    NodeList texts=candidate.getElementsByTagNameNS(W,"t");boolean visible=false;
                    for(int j=0;j<texts.getLength();j++)if(texts.item(j).getTextContent().codePoints().anyMatch(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c)))visible=true;
                    if(visible){boolean safe=true;for(Node child=candidate.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&!is(child,"rPr")&&!is(child,"t"))safe=false;if(safe)run=candidate;break;}
                }
                if(field||run==null)continue;
                String rid="ConSensePdf"+name;while(relationshipIds.contains(rid))rid+="x";relationshipIds.add(rid);
                Element rel=relationships.createElementNS(REL,"Relationship");rel.setAttribute("Id",rid);rel.setAttribute("Type",R+"/hyperlink");rel.setAttribute("Target",URI_PREFIX+uriNonce+":"+name);rel.setAttribute("TargetMode","External");relationships.getDocumentElement().appendChild(rel);
                Element link=main.createElementNS(W,"w:hyperlink");link.setAttributeNS(R,"r:id",rid);paragraph.replaceChild(link,run);link.appendChild(run);linked.add(name);unavailable.remove(name);
            }
            if(linked.isEmpty()){bytes=source.clone();return;}
            entries.put("word/document.xml",serialize(main));entries.put("word/_rels/document.xml.rels",serialize(relationships));
            try(ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out)) {
                for(Map.Entry<String,byte[]> entry:entries.entrySet()){DraftPdfProcess.checkDeadline(deadline);ZipEntry e=new ZipEntry(entry.getKey());e.setTime(0);zip.putNextEntry(e);zip.write(entry.getValue());zip.closeEntry();}zip.finish();bytes=out.toByteArray();
            }
        }catch(DraftPdfConversionException known){throw known;}
        catch(Exception invalid){throw new DraftPdfConversionException(DraftPdfConversionException.Code.INVALID_DOCX,"Cannot create a safe conversion-only marker copy.",invalid);}
    }
    String ownedMarker(String uri) {
        String prefix=URI_PREFIX+uriNonce+":";if(!uri.startsWith(prefix))return null;
        String marker=uri.substring(prefix.length());return linked.contains(marker)?marker:null;
    }
    private static boolean is(Node node,String name){return node instanceof Element&&W.equals(node.getNamespaceURI())&&name.equals(node.getLocalName());}
    private static void count(Map<String,Integer> values,String key){values.put(key,values.getOrDefault(key,0)+1);}
    private static Document parse(byte[] bytes)throws Exception {
        if(bytes==null||bytes.length>32L*1024*1024)throw new IOException("Missing/oversized package XML.");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    private static byte[] serialize(Document doc)throws Exception {
        TransformerFactory f=TransformerFactory.newInstance();f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");Transformer t=f.newTransformer();t.setOutputProperty(OutputKeys.ENCODING,"UTF-8");ByteArrayOutputStream out=new ByteArrayOutputStream();t.transform(new DOMSource(doc),new StreamResult(out));return out.toByteArray();
    }
}
