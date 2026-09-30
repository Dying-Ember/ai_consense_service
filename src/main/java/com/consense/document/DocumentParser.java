package com.consense.document;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.mail.BodyPart;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 多格式文档解析：
 *  PDF 优先取文本层，文本层不足（扫描件）时按页渲染转 OCR；
 *  DOCX / DOC / MSG / EML / TXT / MD 各自解析为按页或整段的纯文本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParser {

    private final ConsenseProperties props;
    private final OcrClient ocrClient;

    public ParsedDocument parse(String fileName, byte[] bytes) {
        String ext = extensionOf(fileName);
        try {
            if ("pdf".equals(ext)) {
                return parsePdf(bytes);
            }
            if ("docx".equals(ext)) {
                return parseDocx(bytes);
            }
            if ("doc".equals(ext)) {
                return parseDoc(bytes);
            }
            if ("msg".equals(ext)) {
                return parseMsg(bytes);
            }
            if ("eml".equals(ext) || "emlx".equals(ext)) {
                return parseEml(bytes);
            }
            return parsePlainText(bytes);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("解析 {} 失败: {}", fileName, e.getMessage());
            throw new BizException(4003, "无法解析文件 " + fileName + "：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ PDF

    private ParsedDocument parsePdf(byte[] bytes) throws Exception {
        try (PDDocument document = PDDocument.load(bytes)) {
            int totalPages = document.getNumberOfPages();
            List<PageText> pages = new ArrayList<>(totalPages);
            PDFTextStripper stripper = new PDFTextStripper();
            int textLayerChars = 0;

            for (int pageNo = 1; pageNo <= totalPages; pageNo++) {
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String text = normalize(stripper.getText(document));
                textLayerChars += text.length();
                pages.add(new PageText(pageNo, text));
            }

            boolean textLayerThin = textLayerChars < (long) props.getOcr().getTextLayerMinChars() * Math.max(1, totalPages);
            if (!textLayerThin) {
                return new ParsedDocument(joinPages(pages), pages, totalPages, false,
                        "PDF 文本层解析，" + totalPages + " 页");
            }

            int ocrPages = ocrPdf(document, pages, totalPages);
            String text = joinPages(pages);
            if (ocrPages == 0 && text.isEmpty()) {
                // 扫描件无文本层，且 OCR 未启用 / 不可用 / 全部页识别失败：
                // 宁可标失败也不能以空文本伪装解析成功，否则下游变量识别拿不到任何内容
                throw new BizException(4003, "文件为扫描件（无文本层）且 OCR 服务不可用（"
                        + props.getOcr().getBaseUrl() + "），请启动 OCR 服务后重新上传");
            }
            String message = ocrPages > 0
                    ? "PDF 文本层过少，已对 " + ocrPages + " 页执行 OCR"
                    : "PDF 文本层过少，OCR 未启用或不可用，仅保留文本层结果";
            return new ParsedDocument(text, pages, totalPages, ocrPages > 0, message);
        }
    }

    private int ocrPdf(PDDocument document, List<PageText> pages, int totalPages) {
        if (!props.getOcr().isEnabled() || !ocrClient.available()) {
            log.warn("扫描件需要 OCR，但 OCR 服务不可用：{}", props.getOcr().getBaseUrl());
            return 0;
        }
        PDFRenderer renderer = new PDFRenderer(document);
        int limit = Math.min(totalPages, props.getOcr().getMaxOcrPages());
        int done = 0;
        for (int pageNo = 1; pageNo <= limit; pageNo++) {
            try {
                BufferedImage image = renderer.renderImageWithDPI(pageNo - 1, 200);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                ImageIO.write(image, "png", buffer);
                OcrClient.OcrResult result = ocrClient.recognize(buffer.toByteArray());
                if (!result.isEmpty()) {
                    pages.set(pageNo - 1, new PageText(pageNo, result.getText()));
                    done++;
                }
            } catch (Exception e) {
                log.warn("第 {} 页 OCR 失败: {}", pageNo, e.getMessage());
            }
        }
        return done;
    }

    // ----------------------------------------------------------------- DOCX

    private ParsedDocument parseDocx(byte[] bytes) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    XWPFParagraph paragraph = (XWPFParagraph) element;
                    String text = paragraph.getText();
                    if (!JsonUtils.isBlankText(text)) {
                        builder.append(text.trim()).append('\n');
                    }
                } else if (element instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) element;
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText().replaceAll("\\s+", " ").trim());
                        }
                        builder.append(String.join(" | ", cells)).append('\n');
                    }
                }
            }
            int pageCount = Math.max(1, document.getProperties().getExtendedProperties() == null
                    ? 1 : 1);
            String text = normalize(builder.toString());
            return new ParsedDocument(text, Collections.singletonList(new PageText(1, text)),
                    pageCount, false, "DOCX 解析完成");
        }
    }

    // ------------------------------------------------------------------ DOC

    private ParsedDocument parseDoc(byte[] bytes) throws Exception {
        String text;
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(document)) {
            text = normalize(extractor.getText());
        }
        return new ParsedDocument(text, Collections.singletonList(new PageText(1, text)),
                1, false, "DOC 解析完成");
    }

    // ------------------------------------------------------------------ MSG

    private ParsedDocument parseMsg(byte[] bytes) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (MAPIMessage message = new MAPIMessage(new ByteArrayInputStream(bytes))) {
            appendHeader(builder, "From", message.getDisplayFrom());
            appendHeader(builder, "To", message.getDisplayTo());
            appendHeader(builder, "Cc", message.getDisplayCC());
            appendHeader(builder, "Subject", message.getSubject());
            builder.append('\n');
            String body = message.getTextBody();
            if (!JsonUtils.isBlankText(body)) {
                builder.append(body.trim());
            } else {
                String html = message.getHtmlBody();
                builder.append(html == null ? "" : stripHtml(html));
            }
        }
        String text = normalize(builder.toString());
        return new ParsedDocument(text, Collections.singletonList(new PageText(1, text)),
                1, false, "MSG 邮件解析完成");
    }

    // ------------------------------------------------------------------ EML

    private ParsedDocument parseEml(byte[] bytes) throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(bytes));
        StringBuilder builder = new StringBuilder();
        appendHeader(builder, "From", message.getHeader("From", null));
        appendHeader(builder, "To", message.getHeader("To", null));
        appendHeader(builder, "Cc", message.getHeader("Cc", null));
        appendHeader(builder, "Subject", message.getHeader("Subject", null));
        appendHeader(builder, "Date", message.getHeader("Date", null));
        builder.append('\n');
        collectPart(message, builder);
        String text = normalize(builder.toString());
        return new ParsedDocument(text, Collections.singletonList(new PageText(1, text)),
                1, false, "EML 邮件解析完成");
    }

    private void collectPart(javax.mail.Part part, StringBuilder builder) throws Exception {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            builder.append(content == null ? "" : content.toString()).append('\n');
            return;
        }
        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            builder.append(content == null ? "" : stripHtml(content.toString())).append('\n');
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                // 附件正文也纳入证据范围
                if (javax.mail.Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())) {
                    builder.append("[附件] ").append(bodyPart.getFileName()).append('\n');
                }
                collectPart(bodyPart, builder);
            }
        }
    }

    // ------------------------------------------------------------------ TXT

    private ParsedDocument parsePlainText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = normalize(text);
        return new ParsedDocument(text, Collections.singletonList(new PageText(1, text)),
                1, false, "纯文本解析完成");
    }

    // -------------------------------------------------------------- helpers

    private void appendHeader(StringBuilder builder, String label, String value) {
        if (!JsonUtils.isBlankText(value)) {
            builder.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private String stripHtml(String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("[ \\t]+", " ");
    }

    private String joinPages(List<PageText> pages) {
        StringBuilder builder = new StringBuilder();
        for (PageText page : pages) {
            if (JsonUtils.isBlankText(page.getText())) {
                continue;
            }
            builder.append("--- P").append(page.getPageNo()).append(" ---\n")
                    .append(page.getText()).append("\n\n");
        }
        return normalize(builder.toString());
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------- results

    /** 解析结果（构造器签名与原 record 一致） */
    public static final class ParsedDocument {

        private final String text;
        private final List<PageText> pages;
        private final int pageCount;
        private final boolean ocrUsed;
        private final String message;

        public ParsedDocument(String text, List<PageText> pages, int pageCount,
                              boolean ocrUsed, String message) {
            this.text = text;
            this.pages = pages;
            this.pageCount = pageCount;
            this.ocrUsed = ocrUsed;
            this.message = message;
        }

        public String getText() {
            return text;
        }

        public List<PageText> getPages() {
            return pages;
        }

        public int getPageCount() {
            return pageCount;
        }

        public boolean isOcrUsed() {
            return ocrUsed;
        }

        public String getMessage() {
            return message;
        }
    }

    public static final class PageText {

        private final int pageNo;
        private final String text;

        public PageText(int pageNo, String text) {
            this.pageNo = pageNo;
            this.text = text;
        }

        public int getPageNo() {
            return pageNo;
        }

        public String getText() {
            return text;
        }
    }
}
