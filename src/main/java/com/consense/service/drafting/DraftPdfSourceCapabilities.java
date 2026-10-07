package com.consense.service.drafting;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Source-side font/field inventory. It does not rewrite OOXML or certify the renderer's glyph coverage. */
final class DraftPdfSourceCapabilities {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String A="http://schemas.openxmlformats.org/drawingml/2006/main";
    private final Map<String,Document> parts=new LinkedHashMap<>();
    private final Map<String,Element> styles=new HashMap<>();
    private final Map<String,String> themes=new HashMap<>();
    private final Set<String> active=new TreeSet<>(),declared=new TreeSet<>(),themeDeclared=new TreeSet<>(),unresolved=new TreeSet<>();
    private Element defaults;
    private String defaultStyle;
    private int fieldInstructions;
    private DraftPdfSourceCapabilities(byte[] bytes) throws Exception {
        long total=0;int count=0;Set<String> names=new HashSet<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;byte[] buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null) {
                if(++count>5000||!names.add(entry.getName()))throw new IOException("Excessive or duplicate package entries.");
                long entryBytes=0;ByteArrayOutputStream xml=new ByteArrayOutputStream();boolean inspect=entry.getName().startsWith("word/")&&entry.getName().endsWith(".xml");int n;
                while((n=zip.read(buffer))!=-1) {
                    total+=n;entryBytes+=n;if(total>256L*1024*1024||entryBytes>128L*1024*1024||(inspect&&entryBytes>32L*1024*1024))throw new IOException("DOCX inflation limit exceeded.");
                    if(inspect)xml.write(buffer,0,n);
                }
                if(inspect)parts.put(entry.getName(),parse(xml.toByteArray()));
            }
        }
        if(!names.contains("[Content_Types].xml")||!parts.containsKey("word/document.xml"))throw new IOException("Not an editable DOCX package.");
        Document stylePart=parts.get("word/styles.xml");
        if(stylePart!=null) {
            defaults=child(child(child(stylePart.getDocumentElement(),"docDefaults"),"rPrDefault"),"rPr");
            NodeList list=stylePart.getElementsByTagNameNS(W,"style");
            for(int i=0;i<list.getLength();i++) {Element s=(Element)list.item(i);styles.put(attr(s,"styleId"),s);
                if("paragraph".equals(attr(s,"type"))&&"1".equals(attr(s,"default")))defaultStyle=attr(s,"styleId");}
        }
        for(Map.Entry<String,Document> part:parts.entrySet()) {
            NodeList fonts=part.getValue().getElementsByTagNameNS(W,"rFonts");
            for(int i=0;i<fonts.getLength();i++)for(String slot:Arrays.asList("ascii","hAnsi","eastAsia","cs")) {
                String value=attr((Element)fonts.item(i),slot);if(!value.isEmpty())declared.add(value);
            }
            if(part.getKey().startsWith("word/theme/")) {
                for(String kind:Arrays.asList("majorFont","minorFont")) {
                    NodeList blocks=part.getValue().getElementsByTagNameNS(A,kind);
                    if(blocks.getLength()==0)continue;Element block=(Element)blocks.item(0);
                    for(String slot:Arrays.asList("latin","ea","cs")) {
                        NodeList choices=block.getElementsByTagNameNS(A,slot);if(choices.getLength()>0)themes.put(kind+":"+slot,((Element)choices.item(0)).getAttribute("typeface"));
                    }
                    NodeList elements=block.getElementsByTagNameNS(A,"*");for(int i=0;i<elements.getLength();i++) {
                        String font=((Element)elements.item(i)).getAttribute("typeface");if(!font.isEmpty())themeDeclared.add(font);
                    }
                }
            }
        }
        for(Map.Entry<String,Document> part:parts.entrySet())if(isStory(part.getKey()))inspectStory(part.getKey(),part.getValue());
    }
    static Map<String,Object> inspect(byte[] bytes,Set<String> installed) {
        final DraftPdfSourceCapabilities source;
        try{source=new DraftPdfSourceCapabilities(bytes);}catch(Exception invalid){throw new DraftPdfConversionException(DraftPdfConversionException.Code.INVALID_DOCX,"Cannot safely inspect the DOCX source package.",invalid);}
        if(!source.unresolved.isEmpty())throw new DraftPdfConversionException(DraftPdfConversionException.Code.FONT_CAPABILITY_UNVERIFIED,"Cannot resolve active source font assignments: "+source.unresolved);
        Set<String> missing=new TreeSet<>();for(String font:source.active)if(installed.stream().noneMatch(found->found.equalsIgnoreCase(font)))missing.add(font);
        if(!missing.isEmpty())throw new DraftPdfConversionException(DraftPdfConversionException.Code.FONT_CAPABILITY_MISSING,"Active source font families are unavailable: "+missing);
        Map<String,Object> result=new LinkedHashMap<>();result.put("activeFamilies",Collections.unmodifiableSet(source.active));
        result.put("sourceStyleRunDeclarations",Collections.unmodifiableSet(source.declared));result.put("themeDeclarations",Collections.unmodifiableSet(source.themeDeclared));
        result.put("activeFamilyAvailability","AVAILABLE");result.put("rendererGlyphCoverage","UNVERIFIED");result.put("fieldInstructions",source.fieldInstructions);
        result.put("inventoryPolicy","Text script slots plus run/character/paragraph styles and document defaults. Unused theme declarations do not require installed fonts.");return Collections.unmodifiableMap(result);
    }
    private void inspectStory(String part,Document document) {
        fieldInstructions+=document.getElementsByTagNameNS(W,"instrText").getLength()+document.getElementsByTagNameNS(W,"fldSimple").getLength();
        NodeList runs=document.getElementsByTagNameNS(W,"r");
        for(int i=0;i<runs.getLength();i++) {
            Element run=(Element)runs.item(i);StringBuilder text=new StringBuilder();NodeList words=run.getElementsByTagNameNS(W,"t");
            for(int n=0;n<words.getLength();n++)text.append(words.item(n).getTextContent());
            Map<String,String> fonts=new HashMap<>();merge(fonts,defaults);style(fonts,defaultStyle,new HashSet<>());
            Element paragraph=ancestor(run,"p");style(fonts,attr(child(child(paragraph,"pPr"),"pStyle"),"val"),new HashSet<>());
            Element rpr=child(run,"rPr");style(fonts,attr(child(rpr,"rStyle"),"val"),new HashSet<>());merge(fonts,rpr);
            Set<String> slots=new HashSet<>();text.toString().codePoints().filter(cp->!Character.isWhitespace(cp)).forEach(cp->slots.add(script(cp)));
            for(String slot:slots) {
                String font=font(fonts,slot);if(font==null||font.isEmpty())unresolved.add(part+"#run"+i+"/"+slot);else active.add(font);
            }
            NodeList symbols=run.getElementsByTagNameNS(W,"sym");for(int n=0;n<symbols.getLength();n++) {
                String font=attr((Element)symbols.item(n),"font");if(font.isEmpty())unresolved.add(part+"#symbol"+n);else active.add(font);
            }
        }
        // OMML defaults are independent of the Latin paragraph font.
        if(document.getElementsByTagNameNS("http://schemas.openxmlformats.org/officeDocument/2006/math","t").getLength()>0) {
            Document settings=parts.get("word/settings.xml");NodeList math=settings==null?null:settings.getElementsByTagNameNS("http://schemas.openxmlformats.org/officeDocument/2006/math","mathFont");
            if(math==null||math.getLength()==0)unresolved.add(part+"/mathFont");else active.add(((Element)math.item(0)).getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/math","val"));
        }
    }
    private String font(Map<String,String> fonts,String slot) {
        String theme=fonts.get(slot+"Theme");
        if(theme!=null)return themes.get((theme.startsWith("major")?"majorFont":"minorFont")+":"+("eastAsia".equals(slot)?"ea":"cs".equals(slot)?"cs":"latin"));
        String font=fonts.get(slot);if(font==null&&"hAnsi".equals(slot))font=fonts.get("ascii");if(font==null&&"ascii".equals(slot))font=fonts.get("hAnsi");return font;
    }
    private void style(Map<String,String> fonts,String id,Set<String> seen) {
        if(id==null||id.isEmpty()||!seen.add(id))return;Element s=styles.get(id);if(s==null){unresolved.add("missing style "+id);return;}
        style(fonts,attr(child(s,"basedOn"),"val"),seen);merge(fonts,child(s,"rPr"));
    }
    private void merge(Map<String,String> fonts,Element rpr) {
        Element choices=child(rpr,"rFonts");if(choices==null)return;
        for(String slot:Arrays.asList("ascii","hAnsi","eastAsia","cs")) {
            String theme=attr(choices,slot+"Theme"),plain=attr(choices,slot);
            if(!theme.isEmpty()){fonts.put(slot+"Theme",theme);fonts.remove(slot);}
            else if(!plain.isEmpty()){fonts.put(slot,plain);fonts.remove(slot+"Theme");}
        }
    }
    private static String script(int cp) {
        if(cp<128)return "ascii";
        if((cp>=0x3000&&cp<=0xd7af)||(cp>=0xf900&&cp<=0xfaff)||(cp>=0xff00&&cp<=0xffef)||(cp>=0x20000&&cp<=0x323af))return "eastAsia";
        if((cp>=0x590&&cp<=0x8ff)||(cp>=0xfb1d&&cp<=0xfdff)||(cp>=0xfe70&&cp<=0xfeff))return "cs";
        return "hAnsi";
    }
    private static boolean isStory(String part) {return part.matches("word/(document|footnotes|endnotes|header[0-9]+|footer[0-9]+)\\.xml");}
    private static String attr(Element element,String name) {return element==null?"":element.getAttributeNS(W,name);}
    private static Element ancestor(Node node,String name) {for(Node p=node.getParentNode();p!=null;p=p.getParentNode())if(p instanceof Element&&W.equals(p.getNamespaceURI())&&name.equals(p.getLocalName()))return (Element)p;return null;}
    private static Element child(Element parent,String name) {if(parent==null)return null;for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&W.equals(n.getNamespaceURI())&&name.equals(n.getLocalName()))return (Element)n;return null;}
    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
}
