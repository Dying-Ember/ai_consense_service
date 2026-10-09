package com.consense.ai;

import com.consense.common.BizException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSink;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 统一 HTTP 调用封装（OkHttp），本地 AI 服务全部走这里。
 */
@Slf4j
@Component
public class HttpSupport {

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectReader OVERLOAD_ERROR_READER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final OkHttpClient shared = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    public String get(String url, long timeoutMs) {
        Request request = new Request.Builder().url(url).get().build();
        return execute(request, timeoutMs, 0);
    }

    public String get(String url, long timeoutMs, String authHeader) {
        Request.Builder request = new Request.Builder().url(url).get();
        if (authHeader != null) request.header("Authorization", authHeader);
        return execute(request.build(), timeoutMs, 0);
    }

    public String postJson(String url, String json, long timeoutMs, String authHeader, int maxRetry) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON));
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        return execute(builder.build(), timeoutMs, maxRetry);
    }

    public String postJson(String url, String json, long timeoutMs) {
        return postJson(url, json, timeoutMs, null, 0);
    }

    /** MiniMax opt-in: retry only a complete HTTP 529 overloaded_error response. */
    public String postJsonWithOverloadBackoff(String url, String json, long timeoutMs, String authHeader) {
        return postJsonWithoutImplicitReplay(url, json, timeoutMs, authHeader, 3);
    }

    /** A retrying MiniMax relay owns the overload policy; this client must send only once. */
    public String postJsonWithoutReplay(String url, String json, long timeoutMs, String authHeader) {
        return postJsonWithoutImplicitReplay(url, json, timeoutMs, authHeader, 1);
    }

    private String postJsonWithoutImplicitReplay(String url, String json, long timeoutMs, String authHeader, int attempts) {
        Request.Builder builder = new Request.Builder().url(url);
        if (authHeader != null) builder.header("Authorization", authHeader);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            // A fresh one-shot body blocks OkHttp's own status/redirect follow-ups.
            // Its immutable JSON bytes remain identical for our explicit 529 attempts.
            Request request = builder.post(oneShotJson(json)).build();
            OkHttpClient client = shared.newBuilder()
                    // A timeout/disconnect can conceal a completed generation; never replay it.
                    .retryOnConnectionFailure(false)
                    .followRedirects(false).followSslRedirects(false)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs + 5_000, TimeUnit.MILLISECONDS).build();
            int status;
            String body;
            try (Response response = client.newCall(request).execute()) {
                status = response.code();
                body = response.body() == null ? "" : response.body().string();
            } catch (IOException failed) {
                throw new BizException("无法连接 " + request.url().host() + ":" + request.url().port()
                        + "，请确认本地服务已启动（" + failed.getMessage() + "）");
            }
            if (status >= 200 && status < 300) return body;
            if (attempt < attempts && explicitOverload(status, body)) {
                long delayMs = attempt == 1 ? 2_000L : 5_000L;
                log.warn("MiniMax HTTP 529 overloaded_error; transport attempt {}/3, next attempt after {} ms", attempt, delayMs);
                try {
                    // The failed response is closed; only our explicit loop may replay the JSON.
                    pauseOverloadRetry(delayMs);
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new BizException("HTTP overload retry interrupted.");
                }
                continue;
            }
            throw new BizException("调用 " + request.url().encodedPath()
                    + " 失败: HTTP " + status + " " + truncate(body));
        }
        throw new IllegalStateException("HTTP overload attempt limit exceeded");
    }

    protected void pauseOverloadRetry(long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
    }

    private RequestBody oneShotJson(String json) {
        final RequestBody body = RequestBody.create(json, JSON);
        return new RequestBody() {
            @Override public MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() throws IOException { return body.contentLength(); }
            @Override public void writeTo(BufferedSink sink) throws IOException { body.writeTo(sink); }
            @Override public boolean isOneShot() { return true; }
        };
    }

    private boolean explicitOverload(int status, String body) {
        if (status != 529) return false;
        try {
            JsonNode root = OVERLOAD_ERROR_READER.readValue(body);
            return root != null && root.isObject() && root.path("error").isObject()
                    && root.path("error").path("type").isTextual()
                    && "overloaded_error".equals(root.path("error").path("type").textValue());
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    public String postMultipart(String url, MultipartBody body, long timeoutMs) {
        Request request = new Request.Builder().url(url).post(body).build();
        return execute(request, timeoutMs, 0);
    }

    public String putJson(String url, String json, long timeoutMs) {
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create(json, JSON))
                .build();
        return execute(request, timeoutMs, 0);
    }

    public String deleteJson(String url, String json, long timeoutMs) {
        Request request = new Request.Builder()
                .url(url)
                .delete(json == null ? null : RequestBody.create(json, JSON))
                .build();
        return execute(request, timeoutMs, 0);
    }

    private String execute(Request request, long timeoutMs, int maxRetry) {
        int attempts = Math.max(1, maxRetry + 1);
        IOException last = null;
        for (int i = 1; i <= attempts; i++) {
            OkHttpClient client = shared.newBuilder()
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new BizException("调用 " + request.url().encodedPath()
                            + " 失败: HTTP " + response.code() + " " + truncate(body));
                }
                return body;
            } catch (IOException e) {
                last = e;
                log.warn("HTTP 调用失败({}/{}): {} - {}", i, attempts, request.url(), e.getMessage());
            }
        }
        throw new BizException("无法连接 " + request.url().host() + ":" + request.url().port()
                + "，请确认本地服务已启动（" + (last == null ? "unknown" : last.getMessage()) + "）");
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
