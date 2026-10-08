package com.consense.ocr;

import com.consense.ai.HttpSupport;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * PaddleOCR / PP-Structure 客户端。
 *
 * 兼容两种常见部署形态：
 *  classic    —— POST {base}{ocrPath}，body {"images":["<base64>"]}，返回 results 二维数组
 *  hubserving —— POST {base}{structurePath}，multipart 字段名 image
 *  local      —— POST {base}/ocr，multipart 字段名 file；GET /health
 *
 * 返回体结构在不同版本间差异较大，这里做宽松解析：只要能拿到 text 字段就收集。
 */
@Slf4j
@RequiredArgsConstructor
public class PaddleOcrClient implements OcrClient {

    private final ConsenseProperties.Ocr cfg;
    private final HttpSupport http;

    @Override
    public OcrResult recognize(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return new OcrResult("", 0.0, Collections.emptyList());
        }
        if ("local".equalsIgnoreCase(cfg.getMode())) {
            return recognizeLocal(imageBytes);
        }
        String raw = "hubserving".equalsIgnoreCase(cfg.getMode())
                ? callHubServing(imageBytes)
                : callClassic(imageBytes);

        List<OcrLine> lines = new ArrayList<>();
        collect(raw, lines);
        if (lines.isEmpty()) {
            log.warn("OCR 未解析出文本，原始响应片段: {}", abbreviate(raw));
            return new OcrResult("", 0.0, Collections.emptyList());
        }
        double sum = 0;
        StringBuilder builder = new StringBuilder();
        for (OcrLine line : lines) {
            builder.append(line.getText()).append('\n');
            sum += line.getConfidence();
        }
        return new OcrResult(builder.toString().trim(), sum / lines.size(), lines);
    }

    @Override
    public boolean available() {
        try {
            String ping = http.get(trim(cfg.getBaseUrl())
                    + ("local".equalsIgnoreCase(cfg.getMode()) ? "/health" : "/"), 3000);
            return ping != null;
        } catch (Exception e) {
            log.debug("OCR 服务不可达: {}", e.getMessage());
            return false;
        }
    }

    private String callClassic(byte[] imageBytes) {
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        ArrayNode images = body.putArray("images");
        images.add(Base64.getEncoder().encodeToString(imageBytes));
        return http.postJson(trim(cfg.getBaseUrl()) + cfg.getOcrPath(),
                JsonUtils.write(body), cfg.getTimeoutMs());
    }

    private String callHubServing(byte[] imageBytes) {
        RequestBody fileBody = RequestBody.create(imageBytes, MediaType.get("image/png"));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "page.png", fileBody)
                .build();
        return http.postMultipart(trim(cfg.getBaseUrl()) + cfg.getStructurePath(),
                body, cfg.getTimeoutMs());
    }

    private OcrResult recognizeLocal(byte[] imageBytes) {
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "page.png",
                        RequestBody.create(imageBytes, MediaType.get("image/png"))).build();
        JsonNode response = JsonUtils.parse(http.postMultipart(trim(cfg.getBaseUrl()) + "/ocr",
                body, cfg.getTimeoutMs()));
        List<OcrLine> lines = new ArrayList<>();
        double confidence = 0;
        StringBuilder text = new StringBuilder();
        for (JsonNode line : response.path("lines")) {
            if (JsonUtils.isBlankText(line.path("text").asText())) continue;
            JsonNode box = line.path("bbox");
            double[] bbox = box.isArray() && box.size() == 4
                    ? new double[]{box.get(0).asDouble(), box.get(1).asDouble(),
                    box.get(2).asDouble(), box.get(3).asDouble()} : null;
            double score = line.path("confidence").asDouble(0);
            lines.add(new OcrLine(line.path("text").asText(), score, bbox));
            text.append(line.path("text").asText()).append('\n');
            confidence += score;
        }
        String fullText = response.path("text").asText(text.toString()).trim();
        return new OcrResult(fullText, lines.isEmpty() ? 0 : confidence / lines.size(), lines);
    }

    /**
     * 递归收集 {text, confidence} 节点，兼容 results 的多种嵌套形态。
     */
    private void collect(String raw, List<OcrLine> out) {
        JsonNode root;
        try {
            root = JsonUtils.parse(raw);
        } catch (Exception e) {
            throw new BizException("OCR 响应不是合法 JSON: " + abbreviate(raw));
        }
        walk(root, out, 0);
    }

    private void walk(JsonNode node, List<OcrLine> out, int depth) {
        if (node == null || depth > 8) {
            return;
        }
        if (node.isArray()) {
            // Paddle's [polygon, [text, confidence]] line form.
            if (node.size() == 2 && node.get(0).isArray() && node.get(1).isArray()
                    && node.get(1).size() >= 2 && node.get(1).get(0).isTextual()) {
                ObjectNode region = JsonUtils.mapper().createObjectNode();
                region.set("points", node.get(0));
                out.add(new OcrLine(node.get(1).get(0).asText(), node.get(1).get(1).asDouble(), extractBbox(region)));
                return;
            }
            node.forEach(child -> walk(child, out, depth + 1));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode textNode = firstPresent(node, "text", "transcription", "rec_text");
        if (textNode != null && !JsonUtils.isBlankText(textNode.asText())) {
            JsonNode scoreNode = firstPresent(node, "confidence", "score", "rec_score");
            double[] bbox = extractBbox(node);
            out.add(new OcrLine(
                    textNode.asText(),
                    scoreNode == null ? 1.0 : scoreNode.asDouble(1.0),
                    bbox));
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isContainerNode() && !"text".equals(entry.getKey())) {
                walk(value, out, depth + 1);
            }
        });
    }

    /**
     * PP-OCR classic 返回结构里 text_region 是 [[x1,y1],[x2,y2],[x3,y3],[x4,y4]] 四点。
     * hubserving/structure 返回里也常见 text_box / bbox / points 等同义字段。
     * 收敛为 {x, y, w, h} 的最小外接矩形；无法识别则返回 null。
     */
    private double[] extractBbox(JsonNode node) {
        JsonNode region = firstPresent(node, "text_region", "text_box", "bbox", "points", "poly");
        if (region == null || !region.isArray()) return null;
        // PP-Structure flat rectangles use x1, y1, x2, y2; local mode is parsed separately as x,y,w,h.
        if (region.size() == 4 && region.get(0).isNumber() && region.get(1).isNumber()
                && region.get(2).isNumber() && region.get(3).isNumber()) {
            double x = region.get(0).asDouble(), y = region.get(1).asDouble();
            return new double[]{x, y, Math.max(0, region.get(2).asDouble() - x),
                    Math.max(0, region.get(3).asDouble() - y)};
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (JsonNode p : region) {
            if (p.isArray() && p.size() >= 2 && p.get(0).isNumber() && p.get(1).isNumber()) {
                double x = p.get(0).asDouble();
                double y = p.get(1).asDouble();
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            } else if (p.isObject() && p.has("x") && p.has("y")) {
                double x = p.get("x").asDouble();
                double y = p.get("y").asDouble();
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            } else {
                return null;
            }
        }
        if (minX == Double.POSITIVE_INFINITY) return null;
        return new double[]{minX, minY, maxX - minX, maxY - minY};
    }

    private JsonNode firstPresent(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
