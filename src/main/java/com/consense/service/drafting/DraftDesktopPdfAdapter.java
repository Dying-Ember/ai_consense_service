package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.Stream;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.*;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.*;

/** Explicit free Desktop Editors x2t route. No paid flags, license bypass, or engine fallback. */
final class DraftDesktopPdfAdapter {
    private static final String POLICY="desktop-x2t.2; PDF513; generated-CS28hex-first-visible-run-nonce-URI-to-XYZ; strip-all-binding-URIs; preserve-unregistered-native-destinations; no-field-or-hyperlink-rewrite";
    private final DraftPdfRenderProfile profile;
    DraftDesktopPdfAdapter(DraftPdfRenderProfile profile){this.profile=profile;}
    String currentProfileHash() {
        long deadline=deadline();checkConfigured();
        try {return identity(deadline).hash;}
        catch(IOException failure){throw failed("Cannot assess configured Desktop x2t assets.",failure);}
    }
    DraftPdfConversionResult convert(byte[] exactDocx) {
        long deadline=deadline();checkConfigured();
        if(exactDocx==null||exactDocx.length==0)throw new DraftPdfConversionException(DraftPdfConversionException.Code.INVALID_DOCX,"The source artifact is empty.");
        byte[] frozen=exactDocx.clone();String inputHash=DraftPdfConverter.sha256(frozen);
        try {
            Identity identity=identity(deadline);
            Map<String,Object> capabilities=DraftPdfSourceCapabilities.inspect(frozen,identity.fontFamilies);
            Files.createDirectories(profile.getWorkRoot());Path work=Files.createTempDirectory(profile.getWorkRoot(),"desktop-render-");
            Path input=work.resolve("artifact.docx");Files.write(input,frozen);
            DraftDesktopMarkerCopy markers=new DraftDesktopMarkerCopy(frozen,deadline);Path transientInput=work.resolve("conversion-copy.docx");Files.write(transientInput,markers.bytes);
            String transientHash=DraftPdfConverter.sha256(markers.bytes);
            Path privateFonts=Files.createDirectory(work.resolve("fonts"));
            Files.copy(profile.getFontCache().resolve("AllFonts.js"),privateFonts.resolve("AllFonts.js"));Files.copy(profile.getFontCache().resolve("font_selection.bin"),privateFonts.resolve("font_selection.bin"));
            Path temp=Files.createDirectory(work.resolve("tmp")),output=work.resolve("artifact.pdf"),params=work.resolve("params.xml");
            String xml="<?xml version=\"1.0\" encoding=\"UTF-8\"?><TaskQueueDataConvert>"+
                tag("m_sFileFrom",transientInput.toString())+tag("m_sFileTo",output.toString())+tag("m_nFormatTo","513")+
                tag("m_sFontDir",privateFonts.toString())+tag("m_sAllFontsPath",privateFonts.resolve("AllFonts.js").toString())+tag("m_sTempDir",temp.toString())+tag("m_bIsNoBase64","true")+"</TaskQueueDataConvert>";
            Files.write(params,xml.getBytes(StandardCharsets.UTF_8));List<String> command=new ArrayList<>(profile.getCommand());command.add(params.toString());
            String diagnostic=DraftPdfProcess.run(profile,command,executable().getParent(),deadline,0);
            if(!inputHash.equals(hash(input,deadline))||!transientHash.equals(hash(transientInput,deadline)))throw failed("Renderer changed a frozen DOCX working copy.",null);
            if(!Files.isRegularFile(output))throw new DraftPdfConversionException(DraftPdfConversionException.Code.OUTPUT_MISSING,"Desktop x2t returned without the expected PDF: "+diagnostic);
            if(Files.size(output)>profile.getMaxPdfBytes())throw new DraftPdfConversionException(DraftPdfConversionException.Code.OUTPUT_TOO_LARGE,"Renderer output exceeds the configured PDF byte limit.");
            byte[] rawPdf=Files.readAllBytes(output);Map<String,Object> manifest=new LinkedHashMap<>();byte[] pdf;
            try(PDDocument parsed=PDDocument.load(rawPdf)) {
                if(parsed.isEncrypted()||parsed.getNumberOfPages()==0)throw new IOException("No accessible PDF pages.");
                Set<String> renderedFonts=new TreeSet<>();for(PDPage page:parsed.getPages())if(page.getResources()!=null)for(COSName key:page.getResources().getFontNames()){PDFont font=page.getResources().getFont(key);renderedFonts.add(font.getName()+"; embedded="+font.isEmbedded());}
                manifest.put("pages",parsed.getNumberOfPages());manifest.put("pdfFonts",renderedFonts);
                Map<String,Object> locations=localDestinations(parsed,markers,deadline);manifest.put("bindingLocations",locations);
                locations.put("strippedOtherBindingActions",stripBindingActions(parsed.getDocument().getTrailer(),deadline));
                try(ByteArrayOutputStream out=new ByteArrayOutputStream()){parsed.save(out);pdf=out.toByteArray();}
            }catch(IOException|RuntimeException invalid){if(invalid instanceof DraftPdfConversionException)throw (DraftPdfConversionException)invalid;throw new DraftPdfConversionException(DraftPdfConversionException.Code.INVALID_PDF,"Desktop output is not a readable non-empty PDF with safe marker locations.",invalid);}
            if(pdf.length>profile.getMaxPdfBytes())throw new DraftPdfConversionException(DraftPdfConversionException.Code.OUTPUT_TOO_LARGE,"Final PDF exceeds the configured PDF byte limit.");
            if(!identity.hash.equals(identity(deadline).hash))throw failed("Renderer assets or cached fonts changed during conversion. Retry the current profile.",null);
            DraftPdfProcess.checkDeadline(deadline);
            manifest.put("docxSha256",inputHash);manifest.put("pdfSha256",DraftPdfConverter.sha256(pdf));manifest.put("rawRendererPdfSha256",DraftPdfConverter.sha256(rawPdf));
            manifest.put("conversionCopySha256",transientHash);manifest.put("conversionCopyPolicy",POLICY);manifest.put("rendererExecutable",executable().toString());manifest.put("rendererVersion",identity.version);
            manifest.put("adapter","explicit free Desktop Editors x2t");manifest.put("exportFilter","PDF513; no paid/license flags");manifest.put("renderProfileHash",identity.hash);manifest.put("rendererAssets",identity.values);
            manifest.put("privateProfileId",privateFonts.toUri().toASCIIString());manifest.put("privateWorkDirectory",work.toString());manifest.put("fontCapability",capabilities);manifest.put("fontInventoryIdentity",profile.getFontInventoryIdentity());
            manifest.put("fontSelectionStatus","UNVERIFIED");manifest.put("fontSelectionWarning","Cached font files are hashed; actual per-run glyph/font fidelity still requires visual assessment.");manifest.put("fieldStatus","UNVERIFIED");manifest.put("fieldPolicy","No exported DOCX cache mutation; PDF conversion does not certify TOC/PAGEREF refresh.");
            manifest.put("rendererOutput",diagnostic);manifest.put("processTermination","owned process tree through runtime ProcessHandle support");
            return new DraftPdfConversionResult(pdf,manifest);
        }catch(IOException failure){throw failed("Cannot run the explicitly configured Desktop x2t renderer.",failure);}
    }
    private Path executable(){return Paths.get(profile.getCommand().get(0)).toAbsolutePath().normalize();}
    private long deadline(){return System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(profile.getTimeoutMillis());}
    private void checkConfigured() {
        if(!DraftPdfProcess.supported()||!Files.isRegularFile(executable())||!Files.isDirectory(profile.getAssetRoot())||!Files.isDirectory(profile.getFontCache()))throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"Desktop x2t requires an explicit executable, runtime asset root, pre-generated font cache and ProcessHandle support.");
        if(!executable().startsWith(profile.getAssetRoot())||profile.getWorkRoot().startsWith(profile.getAssetRoot())||profile.getWorkRoot().startsWith(profile.getFontCache()))throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"Renderer work directories must be separate from immutable runtime/font-cache assets.");
    }
    private Identity identity(long deadline)throws IOException {
        // 9.4's no-argument usage probe identifies the binary but exits with CONVERT_PARAMS (88).
        List<String> probe=new ArrayList<>(profile.getCommand());String usage=DraftPdfProcess.run(profile,probe,executable().getParent(),deadline,88);
        Matcher version=Pattern.compile("OOX/binary file converter\\. Version: ([0-9]+(?:\\.[0-9]+){2,3})").matcher(usage);
        if(!version.find())throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"The configured executable did not identify as Desktop x2t.");
        Identity identity=new Identity();identity.version=version.group(1);identity.values.put("command",profile.getCommand());identity.values.put("version",identity.version);identity.values.put("policy",POLICY);identity.values.put("locale",Locale.getDefault().toLanguageTag());
        identity.values.put("runtimeRoot",profile.getAssetRoot().toString());identity.values.put("runtimeTreeSha256",treeHash(profile.getAssetRoot(),deadline));
        identity.values.put("fontCache",profile.getFontCache().toString());identity.values.put("fontCacheTreeSha256",treeHash(profile.getFontCache(),deadline));
        Path allFonts=profile.getFontCache().resolve("AllFonts.js"),selection=profile.getFontCache().resolve("font_selection.bin");
        if(!Files.isRegularFile(selection)||Files.size(selection)==0||!Files.isRegularFile(allFonts)||Files.size(allFonts)>16L*1024*1024)throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"Missing/oversized pre-generated font cache.");
        String script=new String(Files.readAllBytes(allFonts),StandardCharsets.UTF_8);JsonNode files=array(script,"__fonts_files"),infos=array(script,"__fonts_infos");
        if(!files.isArray()||files.size()==0||files.size()>4000||!infos.isArray())throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"Invalid cached font inventory.");
        Map<String,String> actualFonts=new TreeMap<>();for(JsonNode item:files){if(!item.isTextual())throw new IOException("Invalid cached font path.");Path font=Paths.get(item.asText());if(!font.isAbsolute())throw new IOException("Cached fonts must use explicit absolute paths.");actualFonts.put(font.normalize().toString(),hash(font,deadline));}
        for(JsonNode info:infos)if(info.isArray()&&info.size()>0&&info.get(0).isTextual())identity.fontFamilies.add(info.get(0).asText());
        identity.values.put("actualFontFileCount",actualFonts.size());identity.values.put("actualFontFilesSha256",DraftPdfConverter.sha256(JsonUtils.write(actualFonts).getBytes(StandardCharsets.UTF_8)));
        identity.hash=DraftPdfConverter.sha256(JsonUtils.write(identity.values).getBytes(StandardCharsets.UTF_8));return identity;
    }
    private static JsonNode array(String script,String name)throws IOException {
        Matcher match=Pattern.compile("window\\[\""+name+"\"\\]\\s*=\\s*(\\[.*?\\]);",Pattern.DOTALL).matcher(script);
        if(!match.find())throw new IOException("Required font-cache assignment is missing.");return JsonUtils.mapper().readTree(match.group(1));
    }
    private static String treeHash(Path root,long deadline)throws IOException {
        List<Path> paths=new ArrayList<>();try(Stream<Path> walk=Files.walk(root)){Iterator<Path> items=walk.iterator();while(items.hasNext()){DraftPdfProcess.checkDeadline(deadline);Path p=items.next();if(Files.isSymbolicLink(p))throw new IOException("Runtime inventory cannot contain symbolic links.");if(Files.isRegularFile(p)){if(paths.size()>=50000)throw new IOException("Runtime asset count limit.");paths.add(p);}}}
        paths.sort(Comparator.comparing(p->root.relativize(p).toString()));Map<String,String> values=new LinkedHashMap<>();long total=0;
        for(Path p:paths){total+=Files.size(p);if(total>8L*1024*1024*1024)throw new IOException("Runtime inventory byte limit.");values.put(root.relativize(p).toString(),hash(p,deadline));}
        return DraftPdfConverter.sha256(JsonUtils.write(values).getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(Path path,long deadline)throws IOException {
        if(!Files.isRegularFile(path)||Files.isSymbolicLink(path)||Files.size(path)>512L*1024*1024)throw new IOException("Missing/oversized renderer asset or cached font.");
        try {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){DraftPdfProcess.checkDeadline(deadline);digest.update(b,0,n);}}StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String tag(String key,String value){return "<"+key+">"+value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")+"</"+key+">";}
    private static DraftPdfConversionException failed(String message,Throwable cause){return new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_FAILED,message,cause);}
    private static final class Identity {String version,hash;final Map<String,Object> values=new LinkedHashMap<>();final Set<String> fontFamilies=new TreeSet<>();}

    private static Map<String,Object> localDestinations(PDDocument pdf,DraftDesktopMarkerCopy markers,long deadline)throws IOException {
        Map<String,PDPageDestination> destinations=new TreeMap<>();PDDocumentNameDictionary names=new PDDocumentNameDictionary(pdf.getDocumentCatalog());
        collect(names.getDests(),destinations,0);destinations.keySet().removeIf(markers.registered::contains);
        COSBase legacy=pdf.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.DESTS);
        if(legacy instanceof COSDictionary){COSDictionary d=(COSDictionary)legacy;for(COSName key:new ArrayList<>(d.keySet())){
            DraftPdfProcess.checkDeadline(deadline);if(markers.registered.contains(key.getName())){d.removeItem(key);continue;}
            // Adding /Names can shadow legacy /Dests in readers. Retain the original dictionary
            // and make its unrelated page destinations available in the merged name tree too.
            if(!destinations.containsKey(key.getName())){COSBase value=d.getDictionaryObject(key);if(value instanceof COSDictionary)value=((COSDictionary)value).getDictionaryObject(COSName.D);PDDestination nativePoint=PDDestination.create(value);if(nativePoint instanceof PDPageDestination){if(destinations.size()>=50000)throw new IOException("PDF destination count limit.");destinations.put(key.getName(),(PDPageDestination)nativePoint);}}
        }}
        Set<String> resolved=new LinkedHashSet<>();Map<String,double[]> order=new HashMap<>();int stripped=0,invalid=0,pageIndex=0;
        for(PDPage page:pdf.getPages()) {
            DraftPdfProcess.checkDeadline(deadline);List<PDAnnotation> kept=new ArrayList<>();
            for(PDAnnotation annotation:page.getAnnotations()) {
                if(!(annotation instanceof PDAnnotationLink)||!(((PDAnnotationLink)annotation).getAction() instanceof PDActionURI)){kept.add(annotation);continue;}
                String uri=((PDActionURI)((PDAnnotationLink)annotation).getAction()).getURI();
                if(uri==null||!uri.toLowerCase(Locale.ROOT).startsWith(DraftDesktopMarkerCopy.URI_PREFIX)){kept.add(annotation);continue;}
                stripped++;String marker=markers.ownedMarker(uri);PDRectangle rect=annotation.getRectangle(),crop=page.getCropBox();
                if(marker==null||!valid(rect,crop)||page.getRotation()%90!=0){invalid++;continue;}
                int rotation=(page.getRotation()%360+360)%360;
                float x=rotation==180||rotation==270?rect.getUpperRightX():rect.getLowerLeftX();
                float y=rotation==90||rotation==180?rect.getLowerLeftY():rect.getUpperRightY();
                double[] visual=visual(page,x,y);double[] candidate={pageIndex,visual[1],visual[0]};double[] previous=order.get(marker);
                if(previous==null||before(candidate,previous)){PDPageXYZDestination point=new PDPageXYZDestination();point.setPage(page);point.setLeft(Math.round(x));point.setTop(Math.round(y));point.setZoom(0);point.getCOSObject().set(2,new COSFloat(x));point.getCOSObject().set(3,new COSFloat(y));destinations.put(marker,point);order.put(marker,candidate);resolved.add(marker);}
            }
            page.setAnnotations(kept);pageIndex++;
        }
        PDDestinationNameTreeNode tree=new PDDestinationNameTreeNode();tree.setNames(destinations);names.setDests(tree);pdf.getDocumentCatalog().setNames(names);
        Set<String> missing=new TreeSet<>(markers.unavailable);for(String linked:markers.linked)if(!resolved.contains(linked))missing.add(linked);
        Map<String,Object> report=new LinkedHashMap<>();report.put("kind","point");report.put("policy","actual first existing visible direct text run; not a paragraph rectangle");report.put("sourceUriNamespaceAmbiguous",markers.ambiguousSourceUris);report.put("linkedMarkers",markers.linked.size());report.put("resolvedMarkers",resolved.size());report.put("strippedBindingLinks",stripped);report.put("invalidBindingAnnotations",invalid);report.put("unavailableMarkers",missing);return report;
    }
    private static void collect(PDNameTreeNode<PDPageDestination> tree,Map<String,PDPageDestination> values,int depth)throws IOException {
        if(tree==null)return;if(depth>32||values.size()>50000)throw new IOException("PDF destination tree limit.");if(tree.getNames()!=null)values.putAll(tree.getNames());if(tree.getKids()!=null)for(PDNameTreeNode<PDPageDestination> child:tree.getKids())collect(child,values,depth+1);
    }
    private static boolean valid(PDRectangle r,PDRectangle c) {
        return r!=null&&c!=null&&finite(r.getLowerLeftX())&&finite(r.getLowerLeftY())&&finite(r.getUpperRightX())&&finite(r.getUpperRightY())&&r.getWidth()>0&&r.getHeight()>0&&c.getWidth()>0&&c.getHeight()>0&&r.getLowerLeftX()>=c.getLowerLeftX()&&r.getLowerLeftY()>=c.getLowerLeftY()&&r.getUpperRightX()<=c.getUpperRightX()&&r.getUpperRightY()<=c.getUpperRightY();
    }
    private static boolean finite(float value){return !Float.isInfinite(value)&&!Float.isNaN(value);}
    private static double[] visual(PDPage page,float x,float y) {
        PDRectangle c=page.getCropBox();double left=x-c.getLowerLeftX(),top=c.getUpperRightY()-y;int rotation=(page.getRotation()%360+360)%360;
        if(rotation==90)return new double[]{c.getHeight()-top,left};if(rotation==180)return new double[]{c.getWidth()-left,c.getHeight()-top};if(rotation==270)return new double[]{top,c.getWidth()-left};return new double[]{left,top};
    }
    private static boolean before(double[] a,double[] b){for(int i=0;i<a.length;i++){if(a[i]<b[i])return true;if(a[i]>b[i])return false;}return false;}
    /** Remove custom actions anywhere reachable, including open/hover/next actions, without following links. */
    private static int stripBindingActions(COSBase root,long deadline)throws IOException {
        Set<COSBase> seen=Collections.newSetFromMap(new IdentityHashMap<COSBase,Boolean>());Deque<COSBase> queue=new ArrayDeque<>();queue.add(root);int stripped=0;
        while(!queue.isEmpty()) {
            DraftPdfProcess.checkDeadline(deadline);COSBase node=dereference(queue.removeFirst());if(node==null||!seen.add(node))continue;
            if(seen.size()>200000)throw new IOException("PDF action graph limit.");
            if(node instanceof COSDictionary) {
                COSDictionary dictionary=(COSDictionary)node;
                for(COSName key:new ArrayList<>(dictionary.keySet())){COSBase value=dereference(dictionary.getItem(key));if(bindingAction(value)){dictionary.removeItem(key);stripped++;}else if(value instanceof COSDictionary||value instanceof COSArray)queue.add(value);}
            }else if(node instanceof COSArray) {
                COSArray array=(COSArray)node;for(int i=array.size()-1;i>=0;i--){COSBase value=dereference(array.get(i));if(bindingAction(value)){array.remove(i);stripped++;}else if(value instanceof COSDictionary||value instanceof COSArray)queue.add(value);}
            }
        }
        return stripped;
    }
    private static COSBase dereference(COSBase node){return node instanceof COSObject?((COSObject)node).getObject():node;}
    private static boolean bindingAction(COSBase value) {
        if(!(value instanceof COSDictionary))return false;COSDictionary dictionary=(COSDictionary)value;
        String uri=dictionary.getString(COSName.URI);return COSName.URI.equals(dictionary.getCOSName(COSName.S))&&uri!=null&&uri.toLowerCase(Locale.ROOT).startsWith(DraftDesktopMarkerCopy.URI_PREFIX);
    }
}
