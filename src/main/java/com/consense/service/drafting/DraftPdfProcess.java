package com.consense.service.drafting;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Shared bounded external-process boundary; only descendants of this launch are stopped. */
final class DraftPdfProcess {
    private DraftPdfProcess() { }
    static boolean supported() {try{Process.class.getMethod("toHandle");return true;}catch(NoSuchMethodException absent){return false;}}
    static void checkDeadline(long deadline) {
        if(Thread.currentThread().isInterrupted())throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_FAILED,"Conversion interrupted.");
        if(System.nanoTime()>=deadline)throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_TIMEOUT,"The configured document renderer exceeded its timeout.");
    }
    static String run(DraftPdfRenderProfile profile,List<String> command,Path work,long deadline,int expectedExit)throws IOException {
        checkDeadline(deadline);
        Process process=new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream captured=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{try(InputStream stream=process.getInputStream()) {byte[] buffer=new byte[4096];int count;
            while((count=stream.read(buffer))!=-1)synchronized(captured){int remaining=profile.getMaxOutputBytes()-captured.size();if(remaining>0)captured.write(buffer,0,Math.min(count,remaining));}
        }catch(IOException ignored) { }},"draft-pdf-output");reader.setDaemon(true);reader.start();
        try {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0||!process.waitFor(remaining,TimeUnit.NANOSECONDS)) {
                stopOwned(process);reader.join(1000);
                throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_TIMEOUT,"The configured document renderer exceeded its timeout.");
            }
            reader.join(1000);String output;
            synchronized(captured){output=new String(captured.toByteArray(),StandardCharsets.UTF_8);}
            if(process.exitValue()!=expectedExit)throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_FAILED,"Renderer exit "+process.exitValue()+": "+output);
            return output;
        }catch(InterruptedException interrupted){stopOwned(process);Thread.currentThread().interrupt();throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_FAILED,"Conversion interrupted.",interrupted);}
    }
    private static void stopOwned(Process process) {
        try {
            Class<?> handles=Class.forName("java.lang.ProcessHandle");Object handle=Process.class.getMethod("toHandle").invoke(process);
            try(Stream<?> children=(Stream<?>)handles.getMethod("descendants").invoke(handle)) {
                children.forEach(child->{try{handles.getMethod("destroyForcibly").invoke(child);}catch(Exception ignored){ }});
            }
        }catch(Exception unavailableOnJava8) { }
        process.destroyForcibly();
        try{process.waitFor(1000,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
