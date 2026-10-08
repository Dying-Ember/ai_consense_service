package com.consense.document;

import com.consense.ocr.OcrClient;

/** Per-physical-page observer: delegates the exact byte[] and returns the original result object. */
public final class ObservedOcrClient implements OcrClient {
    private final OcrClient delegate;private final DocumentParseProbe probe;private final int physicalPage;
    public ObservedOcrClient(OcrClient delegate,DocumentParseProbe probe,int physicalPage){this.delegate=delegate;this.probe=probe;this.physicalPage=physicalPage;}
    @Override public boolean available() {
        long started=System.nanoTime();
        try{boolean value=delegate.available();probe.event("ocr_availability",physicalPage,DocumentParseProbe.map("available",value,"wallNanos",System.nanoTime()-started));return value;}
        catch(RuntimeException|Error e){originalFailure("ocr_availability_failure",e,started);throw e;}
    }
    @Override public OcrResult recognize(byte[] imageBytes) {
        probe.binary("ocr_actual_input",physicalPage,DocumentParseProbe.map("boundary","Exact original byte[] passed once to OcrClient; not reconstructed historical input"),imageBytes,"png");
        long started=System.nanoTime();OcrResult result;
        try{result=delegate.recognize(imageBytes);}
        catch(RuntimeException|Error e){originalFailure("ocr_actual_failure",e,started);throw e;}
        long elapsed=System.nanoTime()-started;
        probe.event("ocr_actual_result",physicalPage,DocumentParseProbe.map("wallNanos",elapsed,"seconds",elapsed/1_000_000_000.0,"result",result,
                "ocrQualityStatus","needs_review","ocrAccuracyVerified",false,
                "boundary","Exact downstream OcrResult before normalization; lines/bboxes/confidence are provider declarations, not accuracy verification. Transport raw body is not exposed by OcrClient."));
        return result;
    }
    private void originalFailure(String phase,Throwable original,long started) {
        try{probe.event(phase,physicalPage,DocumentParseProbe.map("wallNanos",System.nanoTime()-started,"failureChain",DocumentParseProbe.failures(original)));}
        catch(DocumentParseProbe.Failure observation){original.addSuppressed(observation);}
    }
}
