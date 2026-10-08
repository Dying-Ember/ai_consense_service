package com.consense.service.drafting;

import java.nio.file.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import java.util.*;
import java.util.zip.*;

/** Only the external renderer executable is doubled; production converter/marker/PDF parsing is real. */
public final class DraftDesktopPdfFixture {
    public static void main(String[] args)throws Exception {
        String mode=args[0];
        if(args.length==1){System.out.println("OOX/binary file converter. Version: 9.4.0.129");System.exit(88);}
        org.w3c.dom.Document xml=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Paths.get(args[1]).toFile());
        Path output=Paths.get(xml.getElementsByTagName("m_sFileTo").item(0).getTextContent());
        Path input=Paths.get(xml.getElementsByTagName("m_sFileFrom").item(0).getTextContent());
        String initialMarker=markerUri(input);
        if("no-output".equals(mode))return;
        if("error".equals(mode)){System.err.println("Deliberate desktop boundary failure");System.exit(17);}
        if("corrupt".equals(mode)){Files.write(output,new byte[]{1,2,3});return;}
        if("large-output".equals(mode))for(int i=0;i<100000;i++)System.out.print('x');
        if("changed-input".equals(mode)){Files.write(Paths.get(xml.getElementsByTagName("m_sFileFrom").item(0).getTextContent()),new byte[]{1,2,3});}
        if("timeout-child".equals(mode)) {
            String javaExecutable=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            Process child=new ProcessBuilder(javaExecutable,"-cp",System.getProperty("java.class.path"),DraftPdfConverterFixture.class.getName(),"unrelated").start();Object handle=Process.class.getMethod("toHandle").invoke(child);long pid=(long)Class.forName("java.lang.ProcessHandle").getMethod("pid").invoke(handle);
            Files.write(output.getParent().resolve("owned-child.pid"),String.valueOf(pid).getBytes(java.nio.charset.StandardCharsets.UTF_8));Thread.sleep(30000);return;
        }
        try(PDDocument pdf=new PDDocument()) {
            PDPage page=new PDPage(new PDRectangle(600,800));page.setCropBox(new PDRectangle(100,200,300,300));page.setRotation("rotate90".equals(mode)?90:0);pdf.addPage(page);
            try(PDPageContentStream stream=new PDPageContentStream(pdf,page)){stream.beginText();stream.setFont(PDType1Font.TIMES_ROMAN,12);stream.newLineAtOffset(120,245);stream.showText("A physical first run");stream.endText();}
            String current=initialMarker;if("unknown-marker".equals(mode))current=current.substring(0,current.lastIndexOf(':')+1)+"CSNotInSource";
            PDAnnotationLink link=new PDAnnotationLink();link.setRectangle("outside-crop".equals(mode)?new PDRectangle(0,0,40,20):new PDRectangle(120,240,40,20));PDActionURI action=new PDActionURI();action.setURI(current);link.setAction(action);page.getAnnotations().add(link);
            if("source-link-collision".equals(mode)){PDAnnotationLink source=new PDAnnotationLink();source.setRectangle(new PDRectangle(180,430,100,12));PDActionURI earlier=new PDActionURI();earlier.setURI("consense-binding:CS0123456789abcdef0123456789ab");source.setAction(earlier);page.getAnnotations().add(source);}
            if("native-destinations".equals(mode)){
                org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination nativePoint=new org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination();nativePoint.setPage(page);nativePoint.setLeft(180);nativePoint.setTop(430);
                org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode tree=new org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode();Map<String,org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination> values=new TreeMap<>();values.put("CSHistorical",nativePoint);values.put("OtherNative",nativePoint);tree.setNames(values);PDDocumentNameDictionary names=new PDDocumentNameDictionary(pdf.getDocumentCatalog());names.setDests(tree);pdf.getDocumentCatalog().setNames(names);org.apache.pdfbox.cos.COSDictionary legacy=new org.apache.pdfbox.cos.COSDictionary();legacy.setItem(org.apache.pdfbox.cos.COSName.getPDFName("CSHistoryLegacy"),nativePoint.getCOSObject());pdf.getDocumentCatalog().getCOSObject().setItem(org.apache.pdfbox.cos.COSName.DESTS,legacy);
            }
            pdf.save(output.toFile());
            if("nested-actions".equals(mode)) {
                PDAnnotationLink ordinary=new PDAnnotationLink();ordinary.setRectangle(new PDRectangle(170,240,40,20));PDActionURI external=new PDActionURI();external.setURI("https://example.invalid/retained-source-link");ordinary.setAction(external);
                org.apache.pdfbox.pdmodel.interactive.action.PDAnnotationAdditionalActions aa=new org.apache.pdfbox.pdmodel.interactive.action.PDAnnotationAdditionalActions();PDActionURI hidden=new PDActionURI();hidden.setURI("consense-binding:ForeignUnknown");aa.setE(hidden);ordinary.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.AA,aa);page.getAnnotations().add(ordinary);pdf.getDocumentCatalog().setOpenAction(hidden);pdf.save(output.toFile());
            }
        }
    }
    private static String markerUri(Path input)throws Exception {
        try(ZipFile zip=new ZipFile(input.toFile())){
            java.util.zip.ZipEntry entry=zip.getEntry("word/_rels/document.xml.rels");if(entry==null)return "consense-binding:CS0123456789abcdef0123456789ab";
            org.w3c.dom.Document relationships=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(zip.getInputStream(entry));org.w3c.dom.NodeList items=relationships.getElementsByTagName("Relationship");
            for(int i=0;i<items.getLength();i++){String target=((org.w3c.dom.Element)items.item(i)).getAttribute("Target");if(target.startsWith("consense-binding:"))return target;}
        }
        return "consense-binding:CS0123456789abcdef0123456789ab";
    }
}
