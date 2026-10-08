package com.consense.service.drafting;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/** A separate executable process fixture, never a production layout fallback. */
public final class DraftPdfConverterFixture {
    public static void main(String[] args) throws Exception {
        String mode=args[0];
        if("timeout-budget".equals(mode))Thread.sleep(700);
        if(Arrays.asList(args).contains("--version")) {System.out.println("FixtureRenderer 1.0");return;}
        if("timeout".equals(mode)) {Thread.sleep(30000);return;}
        if("unrelated".equals(mode)) {Thread.sleep(30000);return;}
        if("timeout-child".equals(mode)) {
            Path input=Paths.get(args[args.length-1]);String java=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),DraftPdfConverterFixture.class.getName(),"unrelated").start();
            Object handle=Process.class.getMethod("toHandle").invoke(child);long pid=(long)Class.forName("java.lang.ProcessHandle").getMethod("pid").invoke(handle);
            Files.write(input.getParent().resolve("owned-child.pid"),String.valueOf(pid).getBytes(StandardCharsets.UTF_8));Thread.sleep(30000);return;
        }
        if("error".equals(mode)) {System.err.println("Deliberate renderer failure");System.exit(17);}
        if("no-output".equals(mode))return;
        Path input=Paths.get(args[args.length-1]),out=null;
        for(int i=0;i<args.length-1;i++)if("--outdir".equals(args[i]))out=Paths.get(args[i+1]);
        if(out==null)throw new IllegalArgumentException("Explicit output directory required");
        Files.createDirectories(out);Path pdf=out.resolve("artifact.pdf");
        if("corrupt".equals(mode)) {Files.write(pdf,"%PDF-not-a-real-file".getBytes(StandardCharsets.UTF_8));return;}
        if("zero".equals(mode)) {Files.write(pdf,new byte[0]);return;}
        if("large-output".equals(mode))for(int i=0;i<100000;i++)System.out.print('x');
        try(PDDocument document=new PDDocument()) {
            PDPage page=new PDPage();document.addPage(page);
            try(PDPageContentStream content=new PDPageContentStream(document,page)) {
                content.beginText();content.setFont(PDType1Font.TIMES_ROMAN,10);content.newLineAtOffset(30,700);
                byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input));StringBuilder hash=new StringBuilder();
                for(byte value:digest)hash.append(String.format(Locale.ROOT,"%02x",value&255));
                content.showText(hash.toString());content.endText();
            }
            document.save(pdf.toFile());
        }
    }
}
