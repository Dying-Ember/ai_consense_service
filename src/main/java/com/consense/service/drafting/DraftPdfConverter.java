package com.consense.service.drafting;

import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import com.consense.common.JsonUtils;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.cos.COSName;

/** Exact artifact bytes in, layout-rendered PDF and provenance out. */
public final class DraftPdfConverter {
    // Preserve invisible saved-document markers as PDF locations for this exact revision.
    private static final String EXPORT_FILTER="pdf:writer_pdf_Export:{\"ExportBookmarksToPDFDestination\":{\"type\":\"boolean\",\"value\":true}}";
    private final DraftPdfRenderProfile profile;
    public DraftPdfConverter(DraftPdfRenderProfile profile) { this.profile=profile; }
    public DraftPdfConversionResult convert(byte[] exactDocx) {
        if(profile!=null&&profile.getEngine()==DraftPdfRenderProfile.Engine.DESKTOP_X2T)return new DraftDesktopPdfAdapter(profile).convert(exactDocx);
        if(profile==null||!Files.isRegularFile(Paths.get(profile.getCommand().get(0))))
            throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"The explicitly configured document renderer is unavailable.");
        if(!hasProcessHandles())throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"This runtime cannot bound the renderer's owned process tree. Use a runtime with ProcessHandle support; no renderer was launched.");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(profile.getTimeoutMillis());
        try {
            if(exactDocx==null||exactDocx.length==0)throw new DraftPdfConversionException(DraftPdfConversionException.Code.INVALID_DOCX,"The source artifact is empty.");
            byte[] frozen=exactDocx.clone();String inputHash=sha256(frozen);
            Map<String,Object> sourceCapabilities=DraftPdfSourceCapabilities.inspect(frozen,profile.getInstalledFonts());
            Files.createDirectories(profile.getWorkRoot());Path work=Files.createTempDirectory(profile.getWorkRoot(),"render-");
            Path privateProfile=Files.createDirectory(work.resolve("profile")),out=Files.createDirectory(work.resolve("out"));
            Path input=work.resolve("artifact.docx");Files.write(input,frozen);
            List<String> versionCommand=new ArrayList<>(profile.getCommand());versionCommand.add("-env:UserInstallation="+privateProfile.toUri().toASCIIString());versionCommand.add("--version");
            String version=run(versionCommand,work,deadline).trim();
            if(version.isEmpty())throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"The renderer did not identify its version.");
            List<String> command=new ArrayList<>(profile.getCommand());
            command.add("-env:UserInstallation="+privateProfile.toUri().toASCIIString());
            String filterArgument=System.getProperty("os.name","").startsWith("Windows")?EXPORT_FILTER.replace("\"","\\\""):EXPORT_FILTER;
            command.addAll(Arrays.asList("--headless","--nologo","--nodefault","--norestore","--convert-to",filterArgument,"--outdir",out.toString(),input.toString()));
            String diagnostic=run(command,work,deadline);
            if(!inputHash.equals(sha256(Files.readAllBytes(input))))throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_FAILED,"Renderer changed the frozen DOCX working copy.");
            Path output=out.resolve("artifact.pdf");
            if(!Files.isRegularFile(output))throw new DraftPdfConversionException(DraftPdfConversionException.Code.OUTPUT_MISSING,"Renderer returned without the expected PDF artifact. Private-profile path length="+privateProfile.toString().length()+"; some portable Windows renderers require an explicitly shorter work root. Output: "+diagnostic);
            if(Files.size(output)>profile.getMaxPdfBytes())throw new DraftPdfConversionException(DraftPdfConversionException.Code.OUTPUT_TOO_LARGE,"Renderer output exceeds the configured PDF byte limit.");
            byte[] pdf=Files.readAllBytes(output);Map<String,Object> manifest=new LinkedHashMap<>();
            try(PDDocument parsed=PDDocument.load(pdf)) {
                if(parsed.isEncrypted()||parsed.getNumberOfPages()==0)throw new IOException("No accessible PDF pages.");
                manifest.put("pages",parsed.getNumberOfPages());Set<String> renderedFonts=new TreeSet<>();
                for(PDPage page:parsed.getPages())if(page.getResources()!=null)for(COSName key:page.getResources().getFontNames()) {
                    PDFont font=page.getResources().getFont(key);renderedFonts.add(font.getName()+"; embedded="+font.isEmbedded());
                }
                manifest.put("pdfFonts",Collections.unmodifiableSet(renderedFonts));
            }catch(IOException|RuntimeException invalid){throw new DraftPdfConversionException(DraftPdfConversionException.Code.INVALID_PDF,"Renderer output is not a readable non-empty PDF.",invalid);}
            manifest.put("docxSha256",inputHash);manifest.put("pdfSha256",sha256(pdf));manifest.put("rendererExecutable",profile.getCommand().get(0));
            manifest.put("rendererVersion",version);manifest.put("adapter","headless writer_pdf_Export");manifest.put("exportFilter",EXPORT_FILTER);
            manifest.put("renderProfileHash",profileHash(version));
            manifest.put("privateProfileId",privateProfile.toUri().toASCIIString());manifest.put("privateWorkDirectory",work.toString());
            manifest.put("fontCapability",sourceCapabilities);manifest.put("fontInventoryIdentity",profile.getFontInventoryIdentity());
            manifest.put("fontInventoryFingerprint",sha256(JsonUtils.write(profile.getInstalledFonts()).getBytes(StandardCharsets.UTF_8)));
            manifest.put("fontSelectionStatus","UNVERIFIED");manifest.put("fontSelectionWarning","Installed family availability and PDF font names are recorded separately. Extra PDF font families may be renderer substitutions; glyph and per-run font fidelity are not certified.");
            manifest.put("fieldStatus","UNVERIFIED");manifest.put("fieldPolicy","No exported DOCX cache mutation; conversion alone does not prove TOC/PAGEREF refresh.");
            manifest.put("rendererOutput",diagnostic);manifest.put("processTermination","owned process tree through runtime ProcessHandle support");
            return new DraftPdfConversionResult(pdf,manifest);
        } catch(IOException failure) {throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_FAILED,"Cannot launch the configured renderer.",failure);}
    }
    /** Assess the actual executable/version/fonts before selecting a cached PDF. */
    public String currentProfileHash() {
        if(profile!=null&&profile.getEngine()==DraftPdfRenderProfile.Engine.DESKTOP_X2T)return new DraftDesktopPdfAdapter(profile).currentProfileHash();
        if(profile==null||!Files.isRegularFile(Paths.get(profile.getCommand().get(0))))throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"The explicitly configured document renderer is unavailable.");
        if(!hasProcessHandles())throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"A runtime with ProcessHandle support is required.");
        try {
            Files.createDirectories(profile.getWorkRoot());Path work=Files.createTempDirectory(profile.getWorkRoot(),"profile-probe-");Path privateProfile=Files.createDirectory(work.resolve("profile"));
            List<String> command=new ArrayList<>(profile.getCommand());command.add("-env:UserInstallation="+privateProfile.toUri().toASCIIString());command.add("--version");
            String version=run(command,work,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(profile.getTimeoutMillis())).trim();
            if(version.isEmpty())throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"The renderer did not identify its version.");
            return profileHash(version);
        }catch(IOException failure){throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_FAILED,"Cannot assess configured renderer.",failure);}
    }
    private String profileHash(String version)throws IOException {
        Map<String,Object> identity=new LinkedHashMap<>();identity.put("command",profile.getCommand());identity.put("version",version);identity.put("exportFilter",EXPORT_FILTER);
        identity.put("executableSha256",sha256(Files.readAllBytes(Paths.get(profile.getCommand().get(0)))));identity.put("adapterVersion",2);
        identity.put("locale",Locale.getDefault().toLanguageTag());identity.put("profilePolicy","fresh private profile for every conversion");
        identity.put("fontInventoryIdentity",profile.getFontInventoryIdentity());identity.put("installedFontFamilies",profile.getInstalledFonts());
        return sha256(JsonUtils.write(identity).getBytes(StandardCharsets.UTF_8));
    }
    public static String sha256(byte[] bytes) {
        try {StringBuilder result=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}
        catch(Exception unavailable){throw new IllegalStateException("SHA-256 unavailable",unavailable);}
    }
    private boolean hasProcessHandles() { return DraftPdfProcess.supported(); }
    private String run(List<String> command,Path work,long deadline) throws IOException {
        return DraftPdfProcess.run(profile,command,work,deadline,0);
    }
}
