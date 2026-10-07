package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Only controlled loopback sockets and fake DNS. No provider DNS/network/credentials are used. */
class VettingResponsesNativeHttpDiagnosticsTest {
    private static final String PRIVATE="SYNTHETIC_PRIVATE_PAYLOAD_NOT_FOR_DIAGNOSTICS";
    private static final AtomicInteger POSTS=new AtomicInteger(),ACCEPTS=new AtomicInteger(),FAKE_DNS=new AtomicInteger();
    private static final List<Map<String,Object>> DIAGNOSTICS=new CopyOnWriteArrayList<>();
    interface Script {void handle(Socket socket)throws Exception;}
    static final class LocalServer implements AutoCloseable {
        final ServerSocket server;final FutureTask<Void> task;final Thread thread;
        LocalServer(Script script,boolean tinyReceive)throws Exception {
            server=new ServerSocket();if(tinyReceive)server.setReceiveBufferSize(1024);server.bind(new InetSocketAddress("127.0.0.1",0),1);server.setSoTimeout(3000);
            task=new FutureTask<>(()->{try(Socket s=server.accept()){ACCEPTS.incrementAndGet();s.setSoTimeout(2000);script.handle(s);}return null;});
            thread=new Thread(task,"synthetic-native-http-loopback");thread.setDaemon(true);thread.start();
        }
        String endpoint(boolean tls){return (tls?"https":"http")+"://127.0.0.1:"+server.getLocalPort()+"/synthetic";}
        public void close()throws Exception {try{task.get(3,TimeUnit.SECONDS);}finally{server.close();thread.join(1000);}}
    }
    static String headers(Socket socket)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();InputStream in=socket.getInputStream();int state=0;
        while(out.size()<16384){int b=in.read();if(b<0)throw new EOFException();out.write(b);state=b==13?(state==2?3:1):b==10?(state==1?2:state==3?4:0):0;if(state==4)return new String(out.toByteArray(),StandardCharsets.US_ASCII);}
        throw new IOException("synthetic headers too large");
    }
    static void response(Socket s,int status,byte[] body,int declared)throws Exception {
        OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+status+" Synthetic\r\nContent-Length: "+declared+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    static VettingResponsesTransport.Response post(VettingResponsesTransport.NativeHttp h,String endpoint,String body,int timeout,int cap){POSTS.incrementAndGet();return h.post(endpoint,body,timeout,cap,"Bearer "+PRIVATE);}
    static VettingResponsesTransport.TypedTransportException failure(VettingResponsesTransport.NativeHttp h,String endpoint,String body,int timeout,int cap){
        VettingResponsesTransport.TypedTransportException e=assertThrows(VettingResponsesTransport.TypedTransportException.class,()->post(h,endpoint,body,timeout,cap));safe(e);DIAGNOSTICS.add(e.getDiagnosticMetadata());return e;
    }
    static void safe(VettingResponsesTransport.TypedTransportException e){
        assertNull(e.getCause());assertEquals(0,e.getSuppressed().length);assertEquals("Responses HTTP transport failed",e.getMessage());assertTrue(e.getWallNanos()>0);assertTrue(e.getBytesRead()>=0);assertEquals("unknown",e.getRootCauseStatus());
        Map<String,Object> m=e.getDiagnosticMetadata();assertEquals(new LinkedHashSet<>(Arrays.asList("phase","category","exceptionClass","wallNanos","responseStatus","bytesRead","rootCauseStatus")),m.keySet());
        for(String value:Arrays.asList(JsonUtils.write(m),JsonUtils.write(e),e.toString())){
            assertFalse(value.contains(PRIVATE));assertFalse(value.contains("127.0.0.1"));assertFalse(value.contains("api.minimax.cn"));assertFalse(value.contains("synthetic.invalid"));assertFalse(value.contains("Bearer"));assertFalse(value.contains("Authorization"));assertFalse(value.contains("requestBody"));assertFalse(value.contains("stackTrace"));
        }
    }
    static VettingResponsesTransport.NativeHttp fakeDns(){return new VettingResponsesTransport.NativeHttp(new OkHttpClient.Builder().dns(host->{FAKE_DNS.incrementAndGet();throw new UnknownHostException(PRIVATE);}).build());}
    @Test void fakeDnsFailureShowsDnsAndOnlyExceptionClassNotMessageOrHost(){
        VettingResponsesTransport.TypedTransportException e=failure(fakeDns(),"https://synthetic.invalid/",PRIVATE,1000,1024);assertEquals("dns",e.getPhase());assertEquals("io_failure",e.getFailureCategory());assertEquals(UnknownHostException.class.getName(),e.getExceptionClassName());assertNull(e.getResponseStatus());assertEquals(0,e.getBytesRead());
    }
    @Test void closedLoopbackConnectFailureHasNoResponse()throws Exception {
        int port;try(ServerSocket s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=s.getLocalPort();}
        VettingResponsesTransport.TypedTransportException e=failure(new VettingResponsesTransport.NativeHttp(),"http://127.0.0.1:"+port+"/",PRIVATE,1000,1024);assertEquals("connect",e.getPhase());assertNull(e.getResponseStatus());assertEquals(0,e.getBytesRead());
    }
    @Test void failedTlsHandshakeRetainsTlsPhase()throws Exception {
        try(LocalServer s=new LocalServer(socket->{socket.getInputStream().read();socket.getOutputStream().write("plain synthetic bytes".getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();},false)){
            VettingResponsesTransport.TypedTransportException e=failure(new VettingResponsesTransport.NativeHttp(),s.endpoint(true),PRIVATE,1000,1024);assertEquals("tls",e.getPhase());assertNull(e.getResponseStatus());assertEquals(0,e.getBytesRead());
        }
    }
    @Test void earlyEofWhileReadingResponseHeadersRemainsHeaders()throws Exception {
        try(LocalServer s=new LocalServer(socket->{headers(socket);},false)){
            VettingResponsesTransport.TypedTransportException e=failure(new VettingResponsesTransport.NativeHttp(),s.endpoint(false),"{}",1000,1024);assertEquals("response_headers",e.getPhase());assertNull(e.getResponseStatus());assertEquals(0,e.getBytesRead());
        }
    }
    @Test void waitingHeadersTimeoutIsNotContextInsufficiencyAndIsNotRetried()throws Exception {
        try(LocalServer s=new LocalServer(socket->{headers(socket);Thread.sleep(400);},false)){
            VettingResponsesTransport.TypedTransportException e=failure(new VettingResponsesTransport.NativeHttp(),s.endpoint(false),"{}",120,1024);assertEquals("response_headers",e.getPhase());assertTrue(Arrays.asList("timeout","timeout_or_interrupted").contains(e.getFailureCategory()));assertNull(e.getResponseStatus());assertFalse(e.getFailureCategory().contains("context"));
        }
    }
    @Test void blockedRequestBodyReportsBodyNotHeadersOrContext()throws Exception {
        try(LocalServer s=new LocalServer(socket->{headers(socket);Thread.sleep(450);},true)){
            char[] data=new char[8*1024*1024];Arrays.fill(data,'x');VettingResponsesTransport.TypedTransportException e=failure(new VettingResponsesTransport.NativeHttp(),s.endpoint(false),new String(data),150,1024);assertEquals("request_body",e.getPhase());assertNull(e.getResponseStatus());assertEquals(0,e.getBytesRead());
        }
    }
    @Test void truncatedBodyKeepsKnownStatusAndActualReadCount()throws Exception {
        try(LocalServer s=new LocalServer(socket->{headers(socket);response(socket,200,new byte[]{'a','b','c'},99);},false)){
            VettingResponsesTransport.TypedTransportException e=failure(new VettingResponsesTransport.NativeHttp(),s.endpoint(false),"{}",1000,1024);assertEquals("response_body",e.getPhase());assertEquals(Integer.valueOf(200),e.getResponseStatus());assertEquals(3,e.getBytesRead());assertEquals("io_failure",e.getFailureCategory());
        }
    }
    @Test void capFailureIsExplicitAndIncludesOverCapReadNotSavedBody()throws Exception {
        try(LocalServer s=new LocalServer(socket->{headers(socket);byte[] data=new byte[2048];Arrays.fill(data,(byte)'x');response(socket,200,data,data.length);},false)){
            VettingResponsesTransport.TypedTransportException e=failure(new VettingResponsesTransport.NativeHttp(),s.endpoint(false),PRIVATE,1000,1024);assertEquals("response_body",e.getPhase());assertEquals("response_cap_exceeded",e.getFailureCategory());assertEquals(Integer.valueOf(200),e.getResponseStatus());assertTrue(e.getBytesRead()>1024&&e.getBytesRead()<=2048);
        }
    }
    @Test void malformedUtf8HasDistinctDecodeCategoryAndKnownByteCount()throws Exception {
        try(LocalServer s=new LocalServer(socket->{headers(socket);response(socket,200,new byte[]{(byte)0xc3,(byte)0xff},2);},false)){
            VettingResponsesTransport.TypedTransportException e=failure(new VettingResponsesTransport.NativeHttp(),s.endpoint(false),"{}",1000,1024);assertEquals("utf8_decode",e.getPhase());assertEquals("invalid_utf8",e.getFailureCategory());assertEquals(Integer.valueOf(200),e.getResponseStatus());assertEquals(2,e.getBytesRead());
        }
    }
    @Test void consecutiveCallsDoNotBorrowStatusBytesOrPhase()throws Exception {
        VettingResponsesTransport.NativeHttp http=new VettingResponsesTransport.NativeHttp();
        try(LocalServer s=new LocalServer(socket->{headers(socket);response(socket,201,new byte[]{(byte)0xc3,(byte)0xff},2);},false)){VettingResponsesTransport.TypedTransportException e=failure(http,s.endpoint(false),"{}",1000,1024);assertEquals(Integer.valueOf(201),e.getResponseStatus());assertEquals(2,e.getBytesRead());}
        int port;try(ServerSocket s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=s.getLocalPort();}
        VettingResponsesTransport.TypedTransportException e=failure(http,"http://127.0.0.1:"+port+"/","{}",1000,1024);assertEquals("connect",e.getPhase());assertNull(e.getResponseStatus());assertEquals(0,e.getBytesRead());
    }
    @Test void successfulUtf8ResponseAndRequestEntityAreUnchanged()throws Exception {
        String body="{\"literal\":\"原文 😀 | cells\"}";byte[] expected=body.getBytes(StandardCharsets.UTF_8);
        try(LocalServer s=new LocalServer(socket->{String h=headers(socket);assertTrue(h.startsWith("POST /synthetic HTTP/1.1\r\n"));assertTrue(h.contains("Authorization: Bearer "+PRIVATE+"\r\n"));assertTrue(h.contains("Content-Type: application/json; charset=utf-8\r\n"));byte[] got=new byte[expected.length];new DataInputStream(socket.getInputStream()).readFully(got);assertArrayEquals(expected,got);response(socket,201,expected,expected.length);},false)){
            VettingResponsesTransport.Response r=post(new VettingResponsesTransport.NativeHttp(),s.endpoint(false),body,1000,1024);assertEquals(201,r.status);assertEquals(body,r.body);
        }
    }
    @Test void typedCounterFailureStillUnknownAndCannotGenerate(){
        ConsenseProperties p=VettingResponsesTransportTest.props();assertEquals(10000,p.getVetting().getResponses().getCounterTimeoutMs());VettingResponsesTransport t=new VettingResponsesTransport(p,(endpoint,body,timeout,cap,auth)->{POSTS.incrementAndGet();return fakeDns().post(endpoint,body,timeout,cap,auth);});
        VettingInputBudget.Input input=VettingResponsesTransportTest.input(t);int before=POSTS.get();VettingInputBudget.Result b=VettingInputBudget.check(input,t::observe);assertEquals("budget_unknown",b.getStatus());assertFalse(b.isExtraDispatchPermitted());assertNull(b.getObservation());assertFalse(b.getReason().contains(PRIVATE));
        assertThrows(Exception.class,()->t.complete(input,b,"Synthetic instruction unchanged.","Native cells ['', 'Room | East 😀 中文']",VettingResponsesTransportTest.schema(),Map.class,new ArrayList<>(),null));assertEquals(before+1,POSTS.get());
    }
    @Test void malformedRequestConfigurationDoesNotClaimNetworkPhase(){VettingResponsesTransport.TypedTransportException e=failure(new VettingResponsesTransport.NativeHttp(),"not a URL "+PRIVATE,PRIVATE,1000,1024);assertEquals("unknown",e.getPhase());assertEquals("configuration_failure",e.getFailureCategory());assertNull(e.getResponseStatus());assertEquals(0,e.getBytesRead());}
    @ParameterizedTest @ValueSource(strings={"dns","connect","tls","request_headers","request_body","response_headers","response_body"})
    void fakeCallbacksDoNotRetainArgumentsAndTerminalCallbackPreservesPhase(String stage){
        VettingResponsesTransport.CallState state=new VettingResponsesTransport.CallState();Call call=org.mockito.Mockito.mock(Call.class);
        switch(stage){case "dns":state.dnsStart(call,PRIVATE);break;case "connect":state.connectStart(call,new InetSocketAddress("127.0.0.1",1),Proxy.NO_PROXY);break;case "tls":state.secureConnectStart(call);break;case "request_headers":state.requestHeadersStart(call);break;case "request_body":state.requestBodyStart(call);break;case "response_headers":state.responseHeadersStart(call);break;case "response_body":state.responseBodyStart(call);break;default:throw new AssertionError();}
        state.callFailed(call,new IOException(PRIVATE));assertEquals(stage,state.phase);assertNull(state.responseStatus);assertEquals(0,state.bytesRead);
        Set<String> fields=new HashSet<>();for(java.lang.reflect.Field f:VettingResponsesTransport.CallState.class.getDeclaredFields())fields.add(f.getName());assertEquals(new HashSet<>(Arrays.asList("startedNanos","phase","lastRequestPhase","responseStatus","bytesRead")),fields);
    }
    @Test void failedRequestFlushDoesNotClaimItWasWaitingForHeaders(){
        VettingResponsesTransport.CallState state=new VettingResponsesTransport.CallState();Call call=org.mockito.Mockito.mock(Call.class);state.requestBodyStart(call);state.requestBodyEnd(call,12);assertEquals("response_headers",state.phase);state.requestFailed(call,new IOException(PRIVATE));assertEquals("request_body",state.phase);assertNull(state.responseStatus);
    }
    @AfterAll static void receipt()throws Exception {
        Map<String,Object> r=new LinkedHashMap<>();r.put("protocol","responses-native-http-safe-diagnostics-software-fixtures-v1");r.put("nativePostInvocations",POSTS.get());r.put("acceptedLoopbackSockets",ACCEPTS.get());r.put("fakeDnsCallbacks",FAKE_DNS.get());r.put("failureDiagnosticRows",DIAGNOSTICS);r.put("externalDnsApiCredentialModelApplicationDbCalls",0);r.put("fixtureDeadlineMsNotProductionConfig",Arrays.asList(120,150,1000));r.put("productionDefaultCounterTimeoutMs",10000);r.put("rootCauseStatus","unknown");
        Path out=Paths.get("target/native-http-diagnostics/receipt.json");Files.createDirectories(out.getParent());Files.write(out,JsonUtils.write(r).getBytes(StandardCharsets.UTF_8));
    }
}
