package com.consense.service.sources;

import com.consense.config.CanonicalEvidenceProperties;
import okhttp3.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.TimeUnit;

/** Only fixed registry paths against an operator-configured base; never a caller URL. */
@Component
public class CanonicalEvidenceBackend {
    private final CanonicalEvidenceProperties properties;
    public CanonicalEvidenceBackend(CanonicalEvidenceProperties properties){this.properties=properties;}
    public static class Reply {
        public final byte[] bytes;
        public final String contentType,etag,sourceSha256,projectionSha256,sourceRevisionSha256,evidenceIdentitySha256;
        Reply(byte[] bytes,String type,String etag,String source,String projection){this(bytes,type,etag,source,projection,null,null);}
        Reply(byte[] bytes,String type,String etag,String source,String projection,String revision,String evidence){
            this.bytes=bytes;this.contentType=type;this.etag=etag;this.sourceSha256=source;this.projectionSha256=projection;
            this.sourceRevisionSha256=revision;this.evidenceIdentitySha256=evidence;}
        public String json(){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
            catch(java.nio.charset.CharacterCodingException invalid){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}}
    }
    public String health(){return get(null,null,null,null,false,null,properties.getMaxSnapshotBytes()).json();}
    public String snapshot(String projection,String module){return get(projection,null,null,null,false,module,Math.min(properties.getMaxSnapshotBytes(),NativeDocumentCollectionContract.MAX_BYTES)).json();}
    /** A batch is addressed by its ordinal under one sealed document root, never by a caller CAS path. */
    public Reply batch(String projection,int ordinal,String module){
        if(projection==null||!projection.matches("[a-f0-9]{64}")||ordinal<0)
            throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"CANONICAL_COLLECTION_BATCH_ADDRESS_INVALID");
        return get(projection,null,null,ordinal,false,module,Math.min(properties.getMaxSnapshotBytes(),NativeDocumentCollectionContract.MAX_BYTES));
    }
    public Reply envelope(String projection,int ordinal,String module){
        if(projection==null||!projection.matches("[a-f0-9]{64}")||ordinal<0)
            throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"CANONICAL_COLLECTION_ENVELOPE_ADDRESS_INVALID");
        if(!java.util.Arrays.asList("drafting","vetting","advisory").contains(module))
            throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"CANONICAL_MODULE_INVALID");
        return get(projection,null,null,ordinal,true,module,Math.min(properties.getMaxSnapshotBytes(),NativeDocumentCollectionContract.MAX_BYTES));
    }
    public Reply asset(String projection,String source,String asset,String module){return get(projection,source,asset,null,false,module,properties.getMaxAssetBytes());}
    public Reply sourcePages(String projection,String source,String evidence,String asset,String module){
        if(projection==null||!projection.matches("[a-f0-9]{64}")||source==null||!source.matches("[a-f0-9]{64}")
                ||evidence==null||!evidence.matches("[a-f0-9]{64}")||!java.util.Arrays.asList("vetting","advisory").contains(module)
                ||asset!=null&&!asset.matches("source-render:[a-f0-9]{64}"))
            throw new CanonicalEvidenceException(HttpStatus.BAD_REQUEST,"CANONICAL_SOURCE_PAGE_ADDRESS_INVALID");
        HttpUrl base=HttpUrl.parse(properties.getBaseUrl()==null?"":properties.getBaseUrl());
        if(base==null||!base.username().isEmpty()||!base.password().isEmpty()||base.query()!=null||base.fragment()!=null
                ||!base.encodedPath().equals("/")||properties.getTimeoutMs()<=0)
            throw new CanonicalEvidenceException(HttpStatus.SERVICE_UNAVAILABLE,"OER_EVIDENCE_CONFIG_INVALID");
        HttpUrl.Builder url=base.newBuilder().addPathSegment("canonical").addPathSegment(projection).addPathSegment("sources")
                .addPathSegment(source).addPathSegment("source-pages");
        if(asset!=null)url.addPathSegment(asset);
        url.addQueryParameter("module",module).addQueryParameter("evidence",evidence);
        return request(url.build(),asset==null?properties.getMaxSnapshotBytes():properties.getMaxAssetBytes());
    }
    private Reply get(String projection,String source,String asset,Integer ordinal,boolean envelope,String module,int maximum){
        HttpUrl base=HttpUrl.parse(properties.getBaseUrl()==null?"":properties.getBaseUrl());
        if(base==null||!base.username().isEmpty()||!base.password().isEmpty()||base.query()!=null||base.fragment()!=null
                ||!base.encodedPath().equals("/")||maximum<=0||properties.getTimeoutMs()<=0)
            throw new CanonicalEvidenceException(HttpStatus.SERVICE_UNAVAILABLE,"OER_EVIDENCE_CONFIG_INVALID");
        HttpUrl.Builder path=base.newBuilder();
        if(projection==null)path.addPathSegment("health");else{
            path.addPathSegment("canonical").addPathSegment(projection);
            if(ordinal!=null)path.addPathSegment(envelope?"envelopes":"batches").addPathSegment(String.valueOf(ordinal));
            if(asset!=null)path.addPathSegment("sources").addPathSegment(source).addPathSegment("assets").addPathSegment(asset);
            path.addQueryParameter("module",module);
        }
        return request(path.build(),maximum);
    }
    /** Transport seam for in-process contract probes; production keeps the same bounded fixed request. */
    protected Reply request(HttpUrl url,int maximum){
        OkHttpClient client=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .readTimeout(properties.getTimeoutMs(),TimeUnit.MILLISECONDS)
                .callTimeout(properties.getTimeoutMs(),TimeUnit.MILLISECONDS).build();
        try(Response response=client.newCall(new Request.Builder().url(url).get().build()).execute()){
            if(!response.isSuccessful()||response.body()==null)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_UPSTREAM_UNAVAILABLE");
            ResponseBody body=response.body();if(body.contentLength()>maximum)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_TOO_LARGE");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
            try(InputStream input=body.byteStream()){while((n=input.read(block))!=-1){if(out.size()>maximum-n)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_TOO_LARGE");out.write(block,0,n);}}
            return new Reply(out.toByteArray(),response.header("Content-Type"),response.header("ETag"),response.header("X-Source-Sha256"),response.header("X-Projection-Sha256"),
                    response.header("X-Source-Revision-Sha256"),response.header("X-Evidence-Identity-Sha256"));
        }catch(IOException error){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"OER_EVIDENCE_UPSTREAM_UNAVAILABLE");}
    }
}
