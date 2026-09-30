package com.consense.ocr;

import com.consense.common.JsonUtils;

import java.util.List;

/**
 * OCR 抽象：当前接本机 PaddleOCR / PP-Structure 服务。
 */
public interface OcrClient {

    /** 识别单张图片（PNG/JPEG 字节） */
    OcrResult recognize(byte[] imageBytes);

    /** 服务是否可达 */
    boolean available();

    /** OCR 识别结果（Java 8 无 record，改为不可变类，构造器签名与原 record 一致） */
    final class OcrResult {

        private final String text;
        private final double confidence;
        private final List<OcrLine> lines;

        public OcrResult(String text, double confidence, List<OcrLine> lines) {
            this.text = text;
            this.confidence = confidence;
            this.lines = lines;
        }

        public String getText() {
            return text;
        }

        public double getConfidence() {
            return confidence;
        }

        public List<OcrLine> getLines() {
            return lines;
        }

        public boolean isEmpty() {
            return JsonUtils.isBlankText(text);
        }
    }

    final class OcrLine {

        private final String text;
        private final double confidence;
        /** 文字在原图坐标系内的 bbox：{x, y, w, h}（四点取最小外接矩形）；可能为 null。 */
        private final double[] bbox;

        public OcrLine(String text, double confidence) {
            this(text, confidence, null);
        }

        public OcrLine(String text, double confidence, double[] bbox) {
            this.text = text;
            this.confidence = confidence;
            this.bbox = bbox;
        }

        public String getText() {
            return text;
        }

        public double getConfidence() {
            return confidence;
        }

        public double[] getBbox() {
            return bbox;
        }
    }
}
