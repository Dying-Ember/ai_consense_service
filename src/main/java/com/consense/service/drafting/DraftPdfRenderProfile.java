package com.consense.service.drafting;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.*;

/** Explicit executable/profile settings. Nothing discovers or downloads a renderer implicitly. */
public final class DraftPdfRenderProfile {
    public enum Engine { LIBRE_OFFICE, DESKTOP_X2T }
    private final List<String> command;
    private final Path workRoot;
    private final long timeoutMillis;
    private final int maxOutputBytes;
    private final long maxPdfBytes;
    private final Set<String> installedFonts;
    private final String fontInventoryIdentity;
    private final Engine engine;
    private final Path assetRoot;
    private final Path fontCache;
    public DraftPdfRenderProfile(List<String> command,Path workRoot,long timeoutMillis,int maxOutputBytes,
                                 long maxPdfBytes,Collection<String> installedFonts,String fontInventoryIdentity) {
        this(command,workRoot,timeoutMillis,maxOutputBytes,maxPdfBytes,installedFonts,fontInventoryIdentity,Engine.LIBRE_OFFICE,null,null);
    }
    public DraftPdfRenderProfile(List<String> command,Path workRoot,long timeoutMillis,int maxOutputBytes,
                                 long maxPdfBytes,Collection<String> installedFonts,String fontInventoryIdentity,
                                 Engine engine,Path assetRoot,Path fontCache) {
        if(command==null||command.isEmpty()||workRoot==null||timeoutMillis<1||timeoutMillis>3600000||maxOutputBytes<1||maxPdfBytes<1||installedFonts==null||fontInventoryIdentity==null)
            throw new IllegalArgumentException("An explicit executable, private work root, positive limits and font inventory are required.");
        if(engine==null||engine==Engine.DESKTOP_X2T&&(assetRoot==null||fontCache==null))throw new IllegalArgumentException("Desktop x2t requires explicit runtime assets and a pre-generated font cache.");
        this.command=Collections.unmodifiableList(new ArrayList<>(command));this.workRoot=workRoot.toAbsolutePath().normalize();
        this.timeoutMillis=timeoutMillis;this.maxOutputBytes=maxOutputBytes;this.maxPdfBytes=maxPdfBytes;
        this.installedFonts=Collections.unmodifiableSet(new TreeSet<>(installedFonts));this.fontInventoryIdentity=fontInventoryIdentity;
        this.engine=engine;this.assetRoot=assetRoot==null?null:assetRoot.toAbsolutePath().normalize();this.fontCache=fontCache==null?null:fontCache.toAbsolutePath().normalize();
    }
    public static DraftPdfRenderProfile libreOffice(Path executable,Path workRoot,long timeoutMillis) {
        return new DraftPdfRenderProfile(Collections.singletonList(executable.toAbsolutePath().toString()),workRoot,timeoutMillis,
                65536,64L*1024*1024,Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)),
                "JVM-visible OS font families; renderer glyph/embedding verification is separate");
    }
    public static DraftPdfRenderProfile desktopX2t(Path executable,Path workRoot,long timeoutMillis,Path assetRoot,Path fontCache) {
        return new DraftPdfRenderProfile(Collections.singletonList(executable.toAbsolutePath().toString()),workRoot,timeoutMillis,
                65536,64L*1024*1024,Collections.emptySet(),"Explicit frozen AllFonts.js/font_selection.bin and actual font file bytes",Engine.DESKTOP_X2T,assetRoot,fontCache);
    }
    public List<String> getCommand() { return command; }
    public Path getWorkRoot() { return workRoot; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public int getMaxOutputBytes() { return maxOutputBytes; }
    public long getMaxPdfBytes() { return maxPdfBytes; }
    public Set<String> getInstalledFonts() { return installedFonts; }
    public String getFontInventoryIdentity() { return fontInventoryIdentity; }
    public Engine getEngine() { return engine; }
    public Path getAssetRoot() { return assetRoot; }
    public Path getFontCache() { return fontCache; }
}
