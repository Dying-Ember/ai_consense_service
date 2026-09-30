package com.consense.service.vetting;

import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.domain.VettingFinding;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审查报告 PDF 生成（PDFBox 直接绘制，A4）。
 * 设计稿用 canvas 手绘导出，这里改成服务端生成，确保三语字体与分页一致。
 */
@Slf4j
@Component
public class VettingPdfWriter {

    private static final float PAGE_W = PDRectangle.A4.getWidth();
    private static final float PAGE_H = PDRectangle.A4.getHeight();
    private static final float MARGIN = 42f;
    private static final float BODY_SIZE = 9.5f;
    private static final float LINE_GAP = 4.2f;
    private static final float CONTENT_W = PAGE_W - MARGIN * 2;

    private static final Map<String, float[]> GROUP_COLOR = new LinkedHashMap<>();
    private static final Map<String, String> GROUP_LABEL_EN = new LinkedHashMap<>();
    private static final Map<String, String> GROUP_LABEL_ZH = new LinkedHashMap<>();

    static {
        GROUP_COLOR.put("reference", new float[]{0.094f, 0.365f, 0.647f});
        GROUP_COLOR.put("conflict", new float[]{0.639f, 0.176f, 0.176f});
        GROUP_COLOR.put("language", new float[]{0.522f, 0.310f, 0.043f});
        GROUP_COLOR.put("risk", new float[]{0.325f, 0.290f, 0.718f});

        GROUP_LABEL_EN.put("reference", "Clause reference error");
        GROUP_LABEL_EN.put("conflict", "Content conflict");
        GROUP_LABEL_EN.put("language", "Language and wording");
        GROUP_LABEL_EN.put("risk", "Subjective risk clause");

        GROUP_LABEL_ZH.put("reference", "条款引用错误");
        GROUP_LABEL_ZH.put("conflict", "内容冲突");
        GROUP_LABEL_ZH.put("language", "语言与用词");
        GROUP_LABEL_ZH.put("risk", "主观风险条款");
    }

    public byte[] write(Project project, List<VettingFinding> findings, String lang) {
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document);
            Cursor cursor = new Cursor(document, font);

            drawCover(cursor, project, findings, lang);
            String currentGroup = null;
            for (VettingFinding finding : findings) {
                if (!finding.getGroupKey().equals(currentGroup)) {
                    currentGroup = finding.getGroupKey();
                    cursor.ensure(46);
                    drawGroupHeader(cursor, currentGroup, countOf(findings, currentGroup));
                }
                drawCard(cursor, finding, lang);
            }
            drawFooters(cursor, lang);
            cursor.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new com.consense.common.BizException(4100, "生成审查报告 PDF 失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 各区块

    private void drawCover(Cursor cursor, Project project, List<VettingFinding> findings, String lang) throws IOException {
        float[] accent = new float[]{0.06f, 0.24f, 0.36f};
        cursor.stream().setNonStrokingColor(accent[0], accent[1], accent[2]);
        cursor.stream().addRect(0, PAGE_H - 132, PAGE_W, 132);
        cursor.stream().fill();

        cursor.y = PAGE_H - 46;
        cursor.textWhite(18, cursor.tr("招标文件审查报告", "招標文件審查報告", "Tender Document Vetting Report", lang));
        cursor.y -= 22;
        cursor.textWhite(10.5f, "ConSense · Tender Document Vetting Report");
        cursor.y -= 20;
        cursor.textWhite(10, cursor.tr(project.getNameZhHans(), project.getNameZhHant(), project.getNameEn(), lang)
                + "  ·  " + nvl(project.getContractNo()));
        cursor.y -= 16;
        cursor.textWhite(9, LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "  ·  " + findings.size() + " findings  ·  "
                + findings.stream().filter(f -> "inter".equals(f.getScope())).count() + " cross-file");

        cursor.y = PAGE_H - 170;
        cursor.text(12, cursor.tr("按问题类型分组的汇总", "按問題類型分組的彙總", "Summary by finding type", lang), true);
        cursor.y -= 8;

        float boxW = (CONTENT_W - 3 * 10) / 4;
        float boxTop = cursor.y;
        String[] groups = {"reference", "conflict", "language", "risk"};
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            float x = MARGIN + i * (boxW + 10);
            float[] color = GROUP_COLOR.get(group);
            cursor.ensure(66);
            cursor.stream().setNonStrokingColor(color[0], color[1], color[2]);
            cursor.stream().addRect(x, boxTop - 62, boxW, 62);
            cursor.stream().fill();

            cursor.textAt(x + 10, boxTop - 20, 8.5f, cursor.tr(GROUP_LABEL_ZH.get(group),
                    groupLabelHant(group), GROUP_LABEL_EN.get(group), lang), true, true);
            cursor.textAt(x + 10, boxTop - 44, 20f, String.valueOf(countOf(findings, group)), true, true);
        }
        cursor.y = boxTop - 78;
        cursor.text(8.5f, cursor.tr("问题按类型分组统计，不使用高中低风险分级。",
                "問題按類型分組統計，不使用高中低風險分級。",
                "Findings are grouped by type; the high/medium/low risk scale is not used.", lang), false);
        cursor.y -= 18;
    }

    private void drawGroupHeader(Cursor cursor, String group, int count) throws IOException {
        float[] color = GROUP_COLOR.getOrDefault(group, new float[]{0.2f, 0.2f, 0.2f});
        cursor.stream().setNonStrokingColor(color[0] * 0.12f + 0.88f, color[1] * 0.12f + 0.88f, color[2] * 0.12f + 0.88f);
        cursor.stream().addRect(MARGIN, cursor.y - 22, CONTENT_W, 22);
        cursor.stream().fill();
        cursor.textAt(MARGIN + 8, cursor.y - 15, 11f, GROUP_LABEL_ZH.get(group) + " / "
                + GROUP_LABEL_EN.get(group) + "   (" + count + ")", true, false);
        cursor.y -= 34;
    }

    private void drawCard(Cursor cursor, VettingFinding finding, String lang) throws IOException {
        float[] color = GROUP_COLOR.getOrDefault(finding.getGroupKey(), new float[]{0.2f, 0.2f, 0.2f});
        String title = finding.getCode() + "  " + localized(finding.getTitleZhHans(),
                finding.getTitleZhHant(), finding.getTitleEn(), lang);
        String meta = nvl(finding.getFileKey()) + " · " + nvl(finding.getPageNo()) + " · " + nvl(finding.getRefs())
                + "   [" + finding.getStatus() + "]"
                + ("inter".equals(finding.getScope()) ? "  ·  " + cursor.tr("跨文件", "跨文件", "cross-file", lang) : "");
        String body = localized(finding.getBodyZhHans(), finding.getBodyZhHant(), finding.getBodyEn(), lang);
        String impact = localized(finding.getImpactZhHans(), finding.getImpactZhHant(), finding.getImpactEn(), lang);
        String suggestion = localized(finding.getSuggestionZhHans(), finding.getSuggestionZhHant(),
                finding.getSuggestionEn(), lang);

        List<String> titleLines = cursor.wrap(title, 10f, CONTENT_W - 22);
        List<String> metaLines = cursor.wrap(meta, 8f, CONTENT_W - 22);
        List<String> bodyLines = cursor.wrap(body, BODY_SIZE, CONTENT_W - 22);
        List<String> impactLines = cursor.wrap(cursor.tr("理由：", "理由：", "Reason: ", lang) + impact,
                BODY_SIZE, CONTENT_W - 22);
        List<String> suggestionLines = cursor.wrap(cursor.tr("建议：", "建議：", "Suggested action: ", lang) + suggestion,
                BODY_SIZE, CONTENT_W - 22);

        float height = 14 + titleLines.size() * 13 + metaLines.size() * 11
                + bodyLines.size() * (BODY_SIZE + LINE_GAP)
                + impactLines.size() * (BODY_SIZE + LINE_GAP)
                + suggestionLines.size() * (BODY_SIZE + LINE_GAP) + 16;

        cursor.ensure(height + 8);
        float top = cursor.y;
        cursor.stream().setStrokingColor(0.85f, 0.87f, 0.89f);
        cursor.stream().addRect(MARGIN, top - height, CONTENT_W, height);
        cursor.stream().stroke();

        cursor.stream().setNonStrokingColor(color[0], color[1], color[2]);
        cursor.stream().addRect(MARGIN, top - height, 3f, height);
        cursor.stream().fill();

        float textY = top - 14;
        for (String line : titleLines) {
            cursor.textAt(MARGIN + 10, textY, 10f, line, true, false);
            textY -= 13;
        }
        for (String line : metaLines) {
            cursor.textAt(MARGIN + 10, textY, 8f, line, false, false);
            textY -= 11;
        }
        textY -= 4;
        for (String line : bodyLines) {
            cursor.textAt(MARGIN + 10, textY, BODY_SIZE, line, false, false);
            textY -= BODY_SIZE + LINE_GAP;
        }
        textY -= 2;
        for (String line : impactLines) {
            cursor.textAt(MARGIN + 10, textY, BODY_SIZE, line, false, false);
            textY -= BODY_SIZE + LINE_GAP;
        }
        for (String line : suggestionLines) {
            cursor.textAt(MARGIN + 10, textY, BODY_SIZE, line, false, false);
            textY -= BODY_SIZE + LINE_GAP;
        }
        cursor.y = top - height - 10;
    }

    private void drawFooters(Cursor cursor, String lang) throws IOException {
        int total = cursor.pageCount();
        for (int i = 0; i < total; i++) {
            PDPageContentStream stream = new PDPageContentStream(cursor.document(), cursor.page(i),
                    true, true);
            stream.setNonStrokingColor(0.55f, 0.58f, 0.62f);
            stream.moveTo(MARGIN, 36);
            stream.lineTo(PAGE_W - MARGIN, 36);
            stream.setStrokingColor(0.85f, 0.87f, 0.89f);
            stream.stroke();
            stream.beginText();
            stream.setFont(cursor.font(), 8f);
            stream.newLineAtOffset(MARGIN, 24);
            stream.showText("ConSense · Vetting Report");
            stream.endText();
            stream.beginText();
            stream.setFont(cursor.font(), 8f);
            stream.newLineAtOffset(PAGE_W / 2 - 40, 24);
            stream.showText(cursor.tr("仅供内部复核", "僅供內部覆核", "Internal review only", lang));
            stream.endText();
            stream.beginText();
            stream.setFont(cursor.font(), 8f);
            stream.newLineAtOffset(PAGE_W - MARGIN - 40, 24);
            stream.showText((i + 1) + " / " + total);
            stream.endText();
            stream.close();
        }
    }

    private int countOf(List<VettingFinding> findings, String group) {
        return (int) findings.stream().filter(f -> group.equals(f.getGroupKey())).count();
    }

    private String groupLabelHant(String group) {
        if ("reference".equals(group)) {
            return "條款引用錯誤";
        }
        if ("conflict".equals(group)) {
            return "內容衝突";
        }
        if ("language".equals(group)) {
            return "語言與用詞";
        }
        return "主觀風險條款";
    }

    private String localized(String zhHans, String zhHant, String en, String lang) {
        return LocalizedText.of(zhHans, zhHant, en).pick(lang);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /**
     * 中文字体：优先 Windows 自带的 simhei.ttf，退回内置 Helvetica（中文会缺字，但至少能出报告）。
     */
    private PDFont loadFont(PDDocument document) {
        for (String path : new String[]{
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/simsun.ttc",
                "C:/Windows/Fonts/msyh.ttc"}) {
            File file = new File(path);
            if (file.exists() && path.endsWith(".ttf")) {
                try {
                    return PDType0Font.load(document, file);
                } catch (IOException e) {
                    log.warn("加载字体 {} 失败: {}", path, e.getMessage());
                }
            }
        }
        try {
            return PDType0Font.load(document, new File("C:/Windows/Fonts/simkai.ttf"));
        } catch (Exception ignored) {
            log.warn("未找到可用中文字体，PDF 中文可能缺字");
        }
        try {
            return org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA;
        } catch (Exception e) {
            throw new com.consense.common.BizException("无法初始化 PDF 字体");
        }
    }

    /** 分页与文字绘制游标 */
    private static final class Cursor {

        private final PDDocument document;
        private final PDFont font;
        private final List<PDPage> pages = new ArrayList<>();
        private PDPageContentStream stream;
        private float y;

        Cursor(PDDocument document, PDFont font) throws IOException {
            this.document = document;
            this.font = font;
            newPage();
        }

        PDDocument document() {
            return document;
        }

        PDFont font() {
            return font;
        }

        PDPageContentStream stream() {
            return stream;
        }

        PDPage page(int index) {
            return pages.get(index);
        }

        int pageCount() {
            return pages.size();
        }

        void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            pages.add(page);
            stream = new PDPageContentStream(document, page);
            y = PAGE_H - MARGIN;
        }

        void ensure(float needed) throws IOException {
            if (y - needed < MARGIN + 30) {
                newPage();
            }
        }

        void text(float size, String value, boolean bold) throws IOException {
            ensure(size + LINE_GAP);
            y -= size;
            textAt(MARGIN, y, size, value, bold, false);
        }

        void textWhite(float size, String value) throws IOException {
            textAt(MARGIN, y, size, value, true, true);
        }

        void textAt(float x, float baseline, float size, String value, boolean bold, boolean white) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            if (white) {
                stream.setNonStrokingColor(1f, 1f, 1f);
            } else if (bold) {
                stream.setNonStrokingColor(0.094f, 0.133f, 0.188f);
            } else {
                stream.setNonStrokingColor(0.29f, 0.31f, 0.35f);
            }
            stream.newLineAtOffset(x, baseline);
            try {
                stream.showText(sanitize(value));
            } catch (IllegalArgumentException e) {
                // 字体缺少字形时跳过该段文字，避免整份报告失败
                log.debug("PDF 文本含不可编码字符，已跳过: {}", e.getMessage());
            }
            stream.endText();
        }

        String tr(String zhHans, String zhHant, String en, String lang) {
            return LocalizedText.of(zhHans, zhHant, en).pick(lang);
        }

        List<String> wrap(String text, float size, float maxWidth) {
            List<String> lines = new ArrayList<>();
            if (text == null || text.trim().isEmpty()) {
                return lines;
            }
            for (String paragraph : text.split("\n")) {
                StringBuilder current = new StringBuilder();
                for (int i = 0; i < paragraph.length(); i++) {
                    char c = paragraph.charAt(i);
                    current.append(c);
                    if (width(current.toString(), size) > maxWidth) {
                        current.deleteCharAt(current.length() - 1);
                        lines.add(current.toString());
                        current = new StringBuilder().append(c);
                    }
                }
                lines.add(current.toString());
            }
            return lines;
        }

        private float width(String text, float size) {
            try {
                return font.getStringWidth(sanitize(text)) / 1000 * size;
            } catch (IOException e) {
                return text.length() * size * 0.5f;
            }
        }

        /** 去掉字体无法编码的字符（如 emoji），避免 showText 抛异常 */
        private String sanitize(String text) {
            if (text == null) {
                return "";
            }
            StringBuilder builder = new StringBuilder(text.length());
            for (char c : text.toCharArray()) {
                if (c < 0x20 && c != '\n') {
                    continue;
                }
                builder.append(c);
            }
            return builder.toString();
        }

        void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }
}
