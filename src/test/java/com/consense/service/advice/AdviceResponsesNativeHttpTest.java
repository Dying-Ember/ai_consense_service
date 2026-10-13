package com.consense.service.advice;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Owned random loopback only. Actual HTTP bytes, no external provider or business/DB request. */
class AdviceResponsesNativeHttpTest {
    @Test void nativeHttpSendsExactCompleteFrozenUtf8BodiesWithoutReframing()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);List<byte[]> received=new ArrayList<>();
        server.createContext("/",exchange->{received.add(exchange.getRequestBody().readAllBytes());byte[] response="{}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
        try{AdviceResponsesTransportTest.Fixture f=new AdviceResponsesTransportTest.Fixture();AdviceResponsesTransport.PreparedRequest prepared=f.operation().prepare("complete system 中文","complete question/material α and table",AdviceResponsesTransportTest.SOURCE,1);
            AdviceResponsesTransport.NativeHttp http=new AdviceResponsesTransport.NativeHttp();String root="http://127.0.0.1:"+server.getAddress().getPort();
            http.post(root+"/v1/responses/input_tokens",prepared.getCounterJson(),5000,1024,"Bearer synthetic-loopback-only");http.post(root+"/v1/responses",prepared.getGenerationJson(),5000,1024,"Bearer synthetic-loopback-only");
            assertEquals(2,received.size());assertArrayEquals(prepared.getCounterJson().getBytes(StandardCharsets.UTF_8),received.get(0));assertArrayEquals(prepared.getGenerationJson().getBytes(StandardCharsets.UTF_8),received.get(1));
        }finally{server.stop(0);}
    }
    @Test void capAndInvalidUtf8RejectWithSafeFailureAndNoReplay()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger calls=new AtomicInteger();server.createContext("/",exchange->{calls.incrementAndGet();byte[] bytes=exchange.getRequestURI().getPath().equals("/cap")?new byte[4096]:new byte[]{(byte)0xc3,(byte)0x28};exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try{AdviceResponsesTransport.NativeHttp http=new AdviceResponsesTransport.NativeHttp();String root="http://127.0.0.1:"+server.getAddress().getPort();AdviceResponsesTransport.RequestFailure cap=assertThrows(AdviceResponsesTransport.RequestFailure.class,()->http.post(root+"/cap","{}",5000,1024,"Bearer synthetic-loopback-only"));assertEquals("response_cap_exceeded",cap.getReason());assertThrows(AdviceResponsesTransport.RequestFailure.class,()->http.post(root+"/utf8","{}",5000,1024,"Bearer synthetic-loopback-only"));assertEquals(2,calls.get());
        }finally{server.stop(0);}
    }
    @Test void redirectsAreReturnedWithoutFollowOrGenerationReplay()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger calls=new AtomicInteger();server.createContext("/",exchange->{calls.incrementAndGet();exchange.getResponseHeaders().add("Location","/second");exchange.sendResponseHeaders(302,-1);exchange.close();});server.start();
        try{String root="http://127.0.0.1:"+server.getAddress().getPort();AdviceResponsesTransport.Response response=new AdviceResponsesTransport.NativeHttp().post(root+"/first","{}",5000,1024,"Bearer synthetic-loopback-only");assertEquals(302,response.status);assertEquals(1,calls.get());}finally{server.stop(0);}
    }
}
