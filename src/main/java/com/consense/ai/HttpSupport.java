package com.consense.ai;

import com.consense.common.BizException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
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

    private final OkHttpClient shared = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    public String get(String url, long timeoutMs) {
        Request request = new Request.Builder().url(url).get().build();
        return execute(request, timeoutMs, 0);
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
