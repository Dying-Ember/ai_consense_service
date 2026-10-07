package com.consense.service.vetting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.web.dto.VettingDtos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Word and PDF exports share one report outline and the same source snapshot. */
@Component
public class VettingReportWriter {
    private static final float MARGIN = 48;
    private static final float WIDTH = PDRectangle.A4.getWidth() - MARGIN * 2;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
            .withZone(ZoneId.of("Asia/Shanghai"));

    @Value("${consense.report.font-path:}")
    private String fontPath = "";

    public byte[] docx(Project project, List<FindingVO> findings, VettingJobVO job, String lang) {
        return docx(project,findings,job,lang,null);
    }
    byte[] docx(Project project,List<FindingVO> findings,VettingJobVO job,String lang,VettingExportProbe probe) {
        List<Block> outline;
        try(VettingExportProbe.Timer timer=VettingExportProbe.measure(probe,"report_outline_construction","Original shared report outline construction")){outline=outline(project,findings,job,lang);}
        try(VettingExportProbe.Timer timer=VettingExportProbe.measure(probe,"docx_ooxml_generation","Original DOCX structure generation/write; not Microsoft Word pagination or visual layout QA")) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            setupWord(document);
            for (Block block : outline) {
                XWPFParagraph paragraph = document.createParagraph();
                if (block.pageBreak) paragraph.setPageBreak(true);
                paragraph.setStyle(block.kind == 3 ? "Title" : block.kind == 2 ? "Heading1" : block.kind == 1 ? "Heading2" : "Normal");
                paragraph.setSpacingAfter(block.compact ? block.kind > 0 ? 20 : 0 : block.kind > 0 ? 160 : 100);
                paragraph.setSpacingBefore(block.kind > 0 && block.kind < 3 ? block.compact ? 80 : 180 : 0);
                paragraph.setSpacingBetween(block.compact ? 1.05 : 1.15);
                paragraph.getCTP().getPPr().addNewWidowControl();
                if (block.kind > 0 || block.keepWithNextOpening) paragraph.getCTP().getPPr().addNewKeepNext();
                if (block.quote) paragraph.setIndentationLeft(240);
                XWPFRun run = paragraph.createRun();
                setWordFont(run);
                run.setFontSize(block.kind == 3 ? 22 : block.kind == 2 ? 14 : block.kind == 1 ? 11 : 10);
                run.setBold(block.kind > 0);
                run.setColor(block.kind > 0 ? "000000" : "222222");
                String[] lines = block.text.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) run.addBreak();
                    run.setText(lines[i]);
                }
            }
            XWPFFooter footer = document.createFooter(HeaderFooterType.DEFAULT);
            XWPFParagraph page = footer.createParagraph();
            page.setAlignment(ParagraphAlignment.RIGHT);
            XWPFRun label = page.createRun(); setWordFont(label); label.setFontSize(8);
            label.setText(tr(lang, "第 ", "第 ", "Page "));
            page.getCTP().addNewFldSimple().setInstr("PAGE");
            XWPFRun separator = page.createRun(); separator.setText(" / "); separator.setFontSize(8);
            page.getCTP().addNewFldSimple().setInstr("NUMPAGES");
            document.getProperties().getCoreProperties().setTitle(tr(lang, "招标文件审查报告", "招標文件審查報告", "Tender Document Vetting Report"));
            document.getProperties().getCoreProperties().setCreator("ConSense");
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            if(probe!=null)probe.originalFailure("docx_original_writer_failure",e);
            throw new BizException(4100, "生成审查报告 Word 失败: " + e.getMessage());
        }
        }
    }

    public byte[] pdf(Project project, List<FindingVO> findings, VettingJobVO job, String lang) {
        return pdf(project,findings,job,lang,null);
    }
    byte[] pdf(Project project,List<FindingVO> findings,VettingJobVO job,String lang,VettingExportProbe probe) {
        List<Block> outline;
        try(VettingExportProbe.Timer timer=VettingExportProbe.measure(probe,"report_outline_construction","Original shared report outline construction")){outline=outline(project,findings,job,lang);}
        try(VettingExportProbe.Timer timer=VettingExportProbe.measure(probe,"pdf_native_generation","Original native PDF font/layout/drawing/footer/save work; not independent visual verification")) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont font = loadFont(document, outline);
            try (PdfCursor cursor = new PdfCursor(document, font)) {
                for (int at = 0; at < outline.size(); at++)
                    cursor.block(outline.get(at), at + 1 < outline.size() ? outline.get(at + 1) : null);
            }
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(i), PDPageContentStream.AppendMode.APPEND, true)) {
                    stream.beginText(); stream.setFont(font, 8); stream.setNonStrokingColor(100, 110, 120);
                    stream.newLineAtOffset(MARGIN, 27);
                    stream.showText(tr(lang, "第 ", "第 ", "Page ") + (i + 1) + " / " + document.getNumberOfPages());
                    stream.endText();
                }
            }
            document.getDocumentInformation().setTitle(tr(lang, "招标文件审查报告", "招標文件審查報告", "Tender Document Vetting Report"));
            document.getDocumentInformation().setCreator("ConSense");
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            if(probe!=null)probe.originalFailure("pdf_original_writer_failure",e);
            throw new BizException(4100, "生成审查报告 PDF 失败: " + e.getMessage());
        }
        }
    }

    private List<Block> outline(Project project, List<FindingVO> findings, VettingJobVO job, String lang) {
        List<Block> blocks = new ArrayList<>();
        List<FindingVO> safeFindings = findings == null ? Collections.emptyList() : findings;
        blocks.add(new Block(3, tr(lang, "招标文件审查报告", "招標文件審查報告", "Tender Document Vetting Report")));
        blocks.add(new Block(0, projectName(project, lang)));
        blocks.add(new Block(0, tr(lang, "合约编号", "合約編號", "Contract number") + ": " + available(project.getContractNo(), lang)));
        blocks.add(new Block(0, tr(lang, "项目编号", "項目編號", "Project ID") + ": " + nvl(project.getId())));
        blocks.add(new Block(0, tr(lang, "任务编号", "任務編號", "Run ID") + ": " + (job == null ? available(null, lang) : nvl(job.getId()))));
        blocks.add(new Block(0, tr(lang, "模型", "模型", "Model") + ": " + (job == null || job.getResult() == null ? available(null, lang) : available(job.getResult().getModel(), lang))));
        blocks.add(new Block(0, tr(lang, "任务完成时间", "任務完成時間", "Run completed") + ": "
                + (job == null || job.getFinishedAt() == null ? available(null, lang) : DATE.format(job.getFinishedAt()))));
        blocks.add(new Block(0, tr(lang, "记录的问题数量", "記錄的問題數量", "Findings recorded") + ": " + safeFindings.size()));
        blocks.add(new Block(0, tr(lang,
                "本报告按源文件和条款组织，记录发现、建议、人工状态及对照原文。审查依据为任务运行时的源文件快照；处理过的片段不代表不存在问题。",
                "本報告按源文件和條款組織，記錄發現、建議、人工狀態及對照原文。審查依據為任務執行時的源文件快照；處理過的片段不代表不存在問題。",
                "This report groups findings by source document and clause, with comments, recommendations, review status and source quotations. It reflects the source snapshot used by the task. A processed segment is not assurance that it contains no issues.")));
        blocks.add(new Block(0, tr(lang,
                "证据已定位仅表示引文能在所用源文件快照中找到，不代表发现的内容或结论已经确认；问题仍须由项目团队复核。",
                "證據已定位僅表示引文能在所用源文件快照中找到，不代表發現的內容或結論已經確認；問題仍須由項目團隊覆核。",
                "Evidence location means quotations were located in the source snapshot; it does not confirm a finding's meaning or conclusion. Findings require project team review.")));

        blocks.add(new Block(2, tr(lang, "文档覆盖情况", "文件覆蓋情況", "Document coverage")));
        CoverageVO coverage = job == null ? null : job.getCoverage();
        if (coverage == null) {
            blocks.add(new Block(0, tr(lang, "没有可核验的任务覆盖记录。", "沒有可核驗的任務覆蓋記錄。", "No verifiable task coverage record is available.")));
        } else {
            blocks.add(new Block(0, tr(lang, "已处理文档", "已處理文件", "Documents processed") + ": " + coverage.getReviewedDocuments() + " / " + coverage.getTotalDocuments()));
            for (DocumentCoverageVO doc : safe(coverage.getDocuments())) {
                blocks.add(compact(new Block(1, available(doc.getFileName(), lang) + "  [" + nvl(doc.getFileKey()) + "]")));
                blocks.add(compact(new Block(0, tr(lang, "解析状态", "解析狀態", "Parse status") + ": " + nvl(doc.getParseStatus()) + "    "
                        + tr(lang, "已处理片段", "已處理片段", "Segments processed") + ": " + doc.getReviewedSegments() + " / " + doc.getTotalSegments() + "    "
                        + tr(lang, "已处理字符", "已處理字元", "Characters processed") + ": " + doc.getReviewedChars() + " / " + doc.getTextChars())));
            }
        }

        blocks.add(new Block(2, tr(lang,"语义审查来源范围","語義審查來源範圍","Semantic source review scope")));
        if(coverage==null||safe(coverage.getSemanticTopics()).isEmpty())blocks.add(new Block(0,tr(lang,"没有来源包覆盖账本，审查范围未知。","沒有來源包覆蓋帳本，審查範圍未知。","Source packet coverage ledger unavailable; review scope unknown.")));
        else for(SemanticTopicVO topic:safe(coverage.getSemanticTopics())) {
            if(topic==null)continue;
            String aggregate=JsonUtils.isBlankText(topic.getAggregateReviewStatus())?"scope_unknown_legacy":topic.getAggregateReviewStatus();
            blocks.add(compact(new Block(1,tr(lang,"主题","主題","Topic")+" "+topic.getTopicIndex()+": "+nvl(topic.getTopic()))));
            blocks.add(compact(new Block(0,tr(lang,"来源范围状态","來源範圍狀態","Source scope status")+": "+aggregate+"    "+tr(lang,"全局调用状态（仅调用及解析）","全局呼叫狀態（僅呼叫及解析）","Global call status (call/decode only)")+": "+nvl(JsonUtils.isBlankText(topic.getGlobalCallStatus())?topic.getStatus():topic.getGlobalCallStatus()))));
            blocks.add(compact(new Block(0,tr(lang,"待处理来源请求","待處理來源請求","Pending source requests")+": "+topic.getPendingSourceRequestCount()+" / "+topic.getSourceRequestCount()+"    "+tr(lang,"额外来源包","額外來源包","Extra source packets")+": "+topic.getExtraPacketCount()+"    "+tr(lang,"失败包","失敗包","Failed packets")+": "+topic.getFailedPacketCount()+"    "+tr(lang,"未提交包","未提交包","Packets not submitted")+": "+topic.getNotSubmittedPacketCount())));
            for(SemanticPacketVO packet:safe(topic.getPacketAudits()))if(packet!=null)blocks.add(compact(new Block(0,tr(lang,"来源包","來源包","Source packet")+" "+packet.getPacketIndex()+": "+nvl(packet.getStatus())+"    "+tr(lang,"输入预算","輸入預算","Input budget")+": "+nvl(packet.getInputBudgetStatus())+(JsonUtils.isBlankText(packet.getFailureKind())?"":"    "+tr(lang,"失败类型","失敗類型","Failure kind")+": "+packet.getFailureKind()))));
        }
        blocks.add(new Block(0,tr(lang,"来源范围状态记录已观察请求的运输、调用及解析；模型的 consistent 或空数组不确认合同无问题，未知限定及遗漏仍须人工核对。","來源範圍狀態記錄已觀察請求的運輸、呼叫及解析；模型的 consistent 或空陣列不確認合同無問題，未知限定及遺漏仍須人工核對。","Source scope status records transport, calls and decoding of observed requests. A model's consistent declaration or empty array does not confirm the contract has no issues; unknown qualifiers and omitted requests still require human review.")));

        Map<String, DocumentFindings> byFile = new LinkedHashMap<>();
        for (FindingVO finding : safeFindings) {
            ReportSource source = reportSource(finding, coverage);
            byFile.computeIfAbsent(source.identity, ignored -> new DocumentFindings(source)).findings.add(finding);
        }
        boolean first = true;
        for (DocumentFindings group : byFile.values()) {
            Block header = new Block(2, tr(lang, "文件", "文件", "Document") + " " + fileLabel(group.source, lang));
            header.pageBreak = first; first = false;
            blocks.add(header);
            for (FindingVO finding : group.findings) {
                blocks.add(new Block(1, nvl(finding.getCode()) + "  " + available(finding.getRefs(), lang)));
                safe(finding.getEvidence()).stream().filter(e->!JsonUtils.isBlankText(e.getPacketId())).map(e->e.getPacketId()+" / "+nvl(e.getPacketSourceSnapshotSha256())).distinct().forEach(identity->blocks.add(compact(new Block(0,tr(lang,"发现的来源包快照","發現的來源包快照","Finding source packet snapshot")+": "+identity))));
                blocks.add(new Block(0, pick(finding.getTitle(), lang)));
                blocks.add(new Block(0, tr(lang, "分类", "分類", "Type") + ": " + String.join(", ", safe(finding.getTypes()))
                        + "    " + tr(lang, "人工状态", "人工狀態", "Review status") + ": " + status(finding.getStatus(), lang)
                        + "    " + tr(lang, "证据定位", "證據定位", "Evidence location") + ": " + verification(finding.getVerification(), lang)));
                blocks.add(new Block(0, tr(lang, "范围", "範圍", "Scope") + ": " + scope(finding.getScope(), lang)
                        + "    " + tr(lang, "严重程度", "嚴重程度", "Severity") + ": " + severity(finding.getSeverity(), lang)
                        + "    " + tr(lang, "检查来源", "檢查來源", "Check source") + ": " + checkSource(finding.getSource(), lang)));
                detail(blocks, tr(lang, "条款位置", "條款位置", "Clause location"), finding.getLocation());
                detail(blocks, tr(lang, "评语", "評語", "Comment"), pick(finding.getBody(), lang));
                detail(blocks, tr(lang, "理由与影响", "理由與影響", "Reason and impact"), pick(finding.getImpact(), lang));
                detail(blocks, tr(lang, "对照要求", "對照要求", "Expected basis"), finding.getExpected());
                detail(blocks, tr(lang, "建议", "建議", "Recommendation"), pick(finding.getSuggestion(), lang));
                blocks.add(new Block(1, tr(lang, "项目团队复核", "項目團隊覆核", "Project team review")));
                detail(blocks, tr(lang, "项目团队回复", "項目團隊回覆", "Remarks by project team"),
                        JsonUtils.isBlankText(finding.getReviewRemarks()) ? tr(lang, "未记录", "未記錄", "Not recorded") : finding.getReviewRemarks());
                detail(blocks, tr(lang, "实际处理说明", "實際處理說明", "Action taken"),
                        JsonUtils.isBlankText(finding.getActionTaken()) ? tr(lang, "未记录", "未記錄", "Not recorded") : finding.getActionTaken());
                detail(blocks, tr(lang, "是否需纳入招标补遗", "是否需納入招標補遺", "Include in tender addendum"),
                        finding.getAddendumRequired() == null ? tr(lang, "待决定", "待決定", "Undecided")
                                : finding.getAddendumRequired() ? tr(lang, "需要", "需要", "Required") : tr(lang, "不需要", "不需要", "Not required"));
                if (finding.getReviewUpdatedAt() != null)
                    detail(blocks, tr(lang, "复核记录保存时间", "覆核記錄儲存時間", "Review record saved at"), finding.getReviewUpdatedAt().toString());
                if (safe(finding.getEvidence()).isEmpty()) {
                    blocks.add(new Block(0, tr(lang, "未提供可定位的原文证据，需要人工复核。", "未提供可定位的原文證據，需要人工覆核。", "No located source evidence is provided. Human review is required.")));
                }
                for (FindingEvidence evidence : safe(finding.getEvidence())) {
                    String position = nvl(evidence.getPageNo());
                    if (!nvl(evidence.getAnchor()).isEmpty()) position += (position.isEmpty() ? "" : " / ") + evidence.getAnchor();
                    Block label = new Block(0, tr(lang, "证据", "證據", "Evidence") + " [" + nvl(evidence.getSide()) + "] "
                            + available(evidence.getFileName(), lang) + "  " + available(position, lang) + "  "
                            + (evidence.isLocated() ? tr(lang, "已定位原文", "已定位原文", "Located in source") : tr(lang, "未定位原文", "未定位原文", "Not located in source")));
                    label.keepWithNextOpening = true; blocks.add(label);
                    Block quote = new Block(0, nvl(evidence.getQuote())); quote.quote = true; blocks.add(quote);
                }
            }
        }
        if (safeFindings.isEmpty()) blocks.add(new Block(0, tr(lang, "本次任务没有记录审查发现。请结合覆盖情况和材料限制解读结果。", "本次任務沒有記錄審查發現。請結合覆蓋情況和材料限制解讀結果。", "No findings were recorded by this task. Interpret this result together with the coverage and material limitations.")));
        blocks.add(new Block(2, tr(lang, "材料缺口与未审范围", "材料缺口與未審範圍", "Material gaps and unreviewed scope")));
        boolean hasWarning = false;
        if (coverage != null) {
            for (String warning : safe(coverage.getWarnings())) { blocks.add(compact(new Block(0, warning(warning, lang)))); hasWarning = true; }
            for (DocumentCoverageVO doc : safe(coverage.getDocuments())) {
                for (String warning : safe(doc.getWarnings())) { blocks.add(compact(new Block(0, nvl(doc.getFileName()) + ": " + warning(warning, lang)))); hasWarning = true; }
                if (doc.getReviewedSegments() < doc.getTotalSegments() || doc.getReviewedChars() < doc.getTextChars() || doc.getTextChars() == 0) {
                    blocks.add(compact(new Block(0, nvl(doc.getFileName()) + ": " + tr(lang, "文档未全部处理或没有可用正文，请核对覆盖记录。", "文件未全部處理或沒有可用正文，請核對覆蓋記錄。", "The document was not fully processed or has no usable text. Check its coverage record."))));
                    hasWarning = true;
                }
            }
        }
        if (!hasWarning) blocks.add(new Block(0, tr(lang, "任务没有记录额外材料缺口；这不构成对未提供文件的完整性确认。", "任務沒有記錄額外材料缺口；這不構成對未提供文件的完整性確認。", "No additional material gaps were recorded. This is not confirmation that documents absent from the source set are complete.")));
        return blocks;
    }

    private void setupWord(XWPFDocument document) {
        CTSectPr section = document.getDocument().getBody().addNewSectPr();
        CTPageSz size = section.addNewPgSz(); size.setW(BigInteger.valueOf(11906)); size.setH(BigInteger.valueOf(16838));
        CTPageMar margins = section.addNewPgMar();
        margins.setTop(BigInteger.valueOf(960)); margins.setBottom(BigInteger.valueOf(960));
        margins.setLeft(BigInteger.valueOf(960)); margins.setRight(BigInteger.valueOf(960));
        margins.setFooter(BigInteger.valueOf(400)); margins.setHeader(BigInteger.valueOf(400));
        XWPFStyles styles = document.createStyles();
        for (String name : Arrays.asList("Normal", "Title", "Heading1", "Heading2")) {
            CTStyle style = CTStyle.Factory.newInstance(); style.setStyleId(name); style.setType(STStyleType.PARAGRAPH);
            style.addNewName().setVal(name); if (!"Normal".equals(name)) style.addNewBasedOn().setVal("Normal");
            CTRPr properties = style.addNewRPr();
            CTFonts fonts = properties.addNewRFonts(); fonts.setAscii("Arial"); fonts.setHAnsi("Arial"); fonts.setEastAsia("Microsoft YaHei");
            properties.addNewColor().setVal("000000");
            if (name.startsWith("Heading")) style.addNewPPr().addNewOutlineLvl().setVal(BigInteger.valueOf("Heading1".equals(name) ? 0 : 1));
            styles.addStyle(new XWPFStyle(style));
        }
    }

    private void setWordFont(XWPFRun run) {
        run.setFontFamily("Arial"); run.setFontFamily("Microsoft YaHei", XWPFRun.FontCharRange.eastAsia);
    }

    private PDFont loadFont(PDDocument document, List<Block> blocks) throws IOException {
        List<String> candidates = new ArrayList<>();
        if (!nvl(fontPath).trim().isEmpty()) candidates.add(fontPath.trim());
        candidates.addAll(Arrays.asList("C:/Windows/Fonts/simhei.ttf", "C:/Windows/Fonts/simkai.ttf",
                "/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf", "/usr/share/fonts/truetype/noto/NotoSansCJKsc-Regular.ttf",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttf"));
        for (String path : candidates) {
            if (!new File(path).isFile()) continue;
            try {
                PDFont font = PDType0Font.load(document, new File(path));
                if (canEncode(font, blocks)) return font;
            } catch (IOException | IllegalArgumentException ignored) { /* Try the next font without losing source text. */ }
        }
        if (canEncode(PDType1Font.HELVETICA, blocks)) return PDType1Font.HELVETICA;
        throw new BizException(4100, "PDF字体不支持报告中的字符，请配置 consense.report.font-path / CONSENSE_REPORT_FONT_PATH 为支持中文的 Unicode TTF 字体文件。");
    }

    private boolean canEncode(PDFont font, List<Block> blocks) throws IOException {
        try {
            for (Block block : blocks) for (String line : block.text.split("\n", -1)) font.getStringWidth(line);
            return true;
        } catch (IllegalArgumentException e) { return false; }
    }

    private static final class Block {
        final int kind; final String text; boolean quote; boolean pageBreak; boolean compact; boolean keepFirstLines; boolean keepWithNextOpening;
        Block(int kind, String text) { this.kind = kind; this.text = nvl(text).replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").replaceAll("[\\p{Cntrl}&&[^\\n]]", ""); }
    }

    private static final class PdfCursor implements AutoCloseable {
        final PDDocument document; final PDFont font; PDPageContentStream stream; float y;
        PdfCursor(PDDocument document, PDFont font) throws IOException { this.document = document; this.font = font; newPage(); }
        void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4); document.addPage(page);
            stream = new PDPageContentStream(document, page); y = PDRectangle.A4.getHeight() - MARGIN;
        }
        void block(Block block, Block next) throws IOException {
            if (block.pageBreak && y < PDRectangle.A4.getHeight() - MARGIN) newPage();
            float size = block.kind == 3 ? 21 : block.kind == 2 ? 14 : block.kind == 1 ? 11 : 9.5f;
            float lineHeight = size * (block.compact ? 1.05f : 1.48f), indent = block.quote ? 12 : 0;
            if (block.kind > 0 && y - lineHeight * 3 < MARGIN) newPage();
            List<String> lines = new ArrayList<>();
            for (String paragraph : block.text.split("\n", -1)) lines.addAll(wrap(paragraph, size, WIDTH - indent));
            if (block.keepWithNextOpening && next != null) {
                float nextSize = next.kind == 3 ? 21 : next.kind == 2 ? 14 : next.kind == 1 ? 11 : 9.5f;
                float nextHeight = nextSize * (next.compact ? 1.05f : 1.48f);
                List<String> nextLines = new ArrayList<>();
                for (String paragraph : next.text.split("\n", -1))
                    nextLines.addAll(wrap(paragraph, nextSize, WIDTH - (next.quote ? 12 : 0)));
                float needed = (block.compact ? 0 : 3) + lineHeight * lines.size() + (block.compact ? 0 : 4)
                        + (next.compact ? 0 : 3) + nextHeight * Math.min(2, nextLines.size());
                // Keep the source label and quotation opening together, while allowing long quotations to paginate.
                if (needed <= PDRectangle.A4.getHeight() - MARGIN * 2 && y - needed < MARGIN) newPage();
            }
            // Keep a labeled detail with its opening lines without locking long source quotations to one page.
            if (block.keepFirstLines && y - 3 - lineHeight * Math.min(2, lines.size()) < MARGIN) newPage();
            y -= block.kind > 0 ? block.compact ? 4 : 9 : block.compact ? 0 : 3;
            for (String line : lines) {
                if (y - lineHeight < MARGIN) newPage();
                stream.beginText(); stream.setFont(font, size);
                stream.setNonStrokingColor(25, 25, 25);
                stream.newLineAtOffset(MARGIN + indent, y); stream.showText(line); stream.endText();
                y -= lineHeight;
            }
            y -= block.compact ? block.kind > 0 ? 1 : 0 : 4;
        }
        List<String> wrap(String text, float size, float width) throws IOException {
            if (text.isEmpty()) return Collections.singletonList("");
            List<String> lines = new ArrayList<>(); StringBuilder line = new StringBuilder(); float used = 0;
            int lastSpace = -1;
            for (int at = 0; at < text.length();) {
                int cp = text.codePointAt(at); String ch = new String(Character.toChars(cp));
                float charWidth = font.getStringWidth(ch) / 1000 * size;
                if (used + charWidth > width && line.length() > 0) {
                    if (lastSpace > 0) {
                        lines.add(line.substring(0, lastSpace));
                        String remainder = line.substring(lastSpace + 1); line.setLength(0); line.append(remainder);
                        used = font.getStringWidth(remainder) / 1000 * size;
                    } else { lines.add(line.toString()); line.setLength(0); used = 0; }
                    lastSpace = line.lastIndexOf(" ");
                }
                if (cp != ' ' || line.length() > 0) { line.append(ch); used += charWidth; if (cp == ' ') lastSpace = line.length() - 1; }
                at += Character.charCount(cp);
            }
            if (line.length() > 0) lines.add(line.toString());
            return lines;
        }
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }

    private void detail(List<Block> blocks, String label, String value) {
        if (!nvl(value).trim().isEmpty()) {
            Block block = new Block(0, label + ": " + value);
            block.keepFirstLines = true;
            blocks.add(block);
        }
    }
    private static Block compact(Block block) { block.compact = true; return block; }
    private ReportSource reportSource(FindingVO finding, CoverageVO coverage) {
        FindingEvidence primary = safe(finding.getEvidence()).stream().filter(Objects::nonNull).findFirst().orElse(null);
        String key = nvl(primary == null ? finding.getFileKey() : primary.getFileKey());
        String documentId = primary == null ? "" : nvl(primary.getDocumentId());
        String fileName = primary == null ? "" : nvl(primary.getFileName());
        if (!documentId.trim().isEmpty()) {
            // A document ID is authoritative; a repeated protocol key is not.
            DocumentCoverageVO document = uniqueCoverage(coverage, documentId, true);
            if (document != null) {
                if (fileName.trim().isEmpty()) fileName = nvl(document.getFileName());
                if (key.trim().isEmpty()) key = nvl(document.getFileKey());
            }
            if (key.trim().isEmpty()) key = nvl(finding.getFileKey());
            return new ReportSource("document:" + documentId, key, fileName);
        }
        if (key.trim().isEmpty()) key = nvl(finding.getFileKey());
        if (!fileName.trim().isEmpty()) return new ReportSource("filename:" + key.length() + ":" + key + fileName, key, fileName);
        if (primary == null) {
            // Legacy records have no source evidence. Only an unambiguous key
            // can supply a filename; never choose the first of multiple sources.
            DocumentCoverageVO document = uniqueCoverage(coverage, key, false);
            if (document != null) {
                documentId = nvl(document.getDocumentId());
                fileName = nvl(document.getFileName());
                if (!documentId.trim().isEmpty()) return new ReportSource("document:" + documentId, key, fileName);
                if (!fileName.trim().isEmpty()) return new ReportSource("filename:" + key.length() + ":" + key + fileName, key, fileName);
            }
        }
        return new ReportSource("unidentified-key:" + key, key, "");
    }
    private DocumentCoverageVO uniqueCoverage(CoverageVO coverage, String value, boolean byDocumentId) {
        if (coverage == null || value.trim().isEmpty()) return null;
        DocumentCoverageVO match = null;
        for (DocumentCoverageVO document : safe(coverage.getDocuments())) {
            if (document != null && value.equals(byDocumentId ? document.getDocumentId() : document.getFileKey())) {
                if (match != null) return null;
                match = document;
            }
        }
        return match;
    }
    private String fileLabel(ReportSource source, String lang) {
        return available(source.key, lang) + (source.fileName.trim().isEmpty() ? "" : "  " + source.fileName);
    }
    private static final class ReportSource {
        final String identity, key, fileName;
        ReportSource(String identity, String key, String fileName) { this.identity = identity; this.key = key; this.fileName = fileName; }
    }
    private static final class DocumentFindings {
        final ReportSource source;
        final List<FindingVO> findings = new ArrayList<>();
        DocumentFindings(ReportSource source) { this.source = source; }
    }
    private String projectName(Project project, String lang) { return LocalizedText.of(project.getNameZhHans(), project.getNameZhHant(), project.getNameEn()).pick(lang); }
    private String status(String value, String lang) {
        if ("HANDLED".equalsIgnoreCase(value)) return tr(lang, "已处理", "已處理", "Handled");
        if ("ASSIGNED".equalsIgnoreCase(value)) return tr(lang, "已分派", "已分派", "Assigned");
        if ("OPEN".equalsIgnoreCase(value)) return tr(lang, "待处理", "待處理", "Open");
        return available(value, lang);
    }
    private String scope(String value, String lang) {
        if ("inter".equals(value)) return tr(lang, "跨文件", "跨文件", "Across documents");
        if ("intra".equals(value)) return tr(lang, "文件内", "文件內", "Within document");
        return available(value, lang);
    }
    private String severity(String value, String lang) {
        if ("high".equalsIgnoreCase(value)) return tr(lang, "高", "高", "High");
        if ("medium".equalsIgnoreCase(value)) return tr(lang, "中", "中", "Medium");
        if ("low".equalsIgnoreCase(value)) return tr(lang, "低", "低", "Low");
        return available(value, lang);
    }
    private String checkSource(String value, String lang) {
        if ("rule".equals(value)) return tr(lang, "规则检查", "規則檢查", "Rule check");
        if ("llm".equals(value) || "model".equals(value)) return tr(lang, "模型审查", "模型審查", "Model review");
        return available(value, lang);
    }
    private String verification(String value, String lang) {
        if ("verified".equals(value)) return tr(lang, "证据已定位", "證據已定位", "Evidence located");
        if ("partial".equals(value)) return tr(lang, "部分证据已定位", "部分證據已定位", "Evidence partly located");
        return tr(lang, "证据待定位", "證據待定位", "Evidence awaiting location");
    }
    private String pick(LocalizedText value, String lang) { return value == null ? "" : value.pick(lang); }
    private String warning(String value, String lang) {
        if (!"en".equalsIgnoreCase(lang) && !"zh-Hant".equalsIgnoreCase(lang)) return nvl(value);
        String source = "OCR 不识别删除线、表格行列关系及图形含义；引文、删除状态和表格对应关系须对照原始物理页复核。";
        String result = nvl(value).replace(source, tr(lang, source,
                "OCR 不識別刪除線、表格行列關係及圖形含義；引文、刪除狀態和表格對應關係須對照原始物理頁覆核。",
                "OCR does not identify strikethrough, table row/column relationships or the meaning of graphics. Quotations, deletion status and table associations must be checked against the original physical pages."));
        // Only known system-warning phrases are localized here. Evidence,
        // comments, filenames and opaque diagnostic details are never translated.
        String[][] phrases = {
                {"未提供可解析的 Bills of Quantities，不能确认数量、提交范围及金额协调。", "未提供可解析的 Bills of Quantities，不能確認數量、提交範圍及金額協調。", "No parseable Bills of Quantities were supplied; quantities, submission scope and monetary coordination cannot be confirmed."},
                {"未提供可解析的 General Summary，无法核对汇总金额。", "未提供可解析的 General Summary，無法核對匯總金額。", "No parseable General Summary was supplied; summary amounts cannot be checked."},
                {"规则扫描全部可解析招标合同条款；标准文件仅作参考对照，项目事实和文件目录仅作上下文，均未当作实际合同执行规则检查。语义覆盖仅计实际成功的模型片段，未检索片段不代表完成语义审查。", "規則掃描全部可解析招標合同條款；標準文件僅作參考對照，項目事實和文件目錄僅作上下文，均未當作實際合同執行規則檢查。語義覆蓋僅計實際成功的模型片段，未檢索片段不代表完成語義審查。", "Rules scan all parseable tender contract provisions. Standards are comparison references; project facts and the package manifest provide context. These supporting sources are not checked as actual contract obligations. Semantic coverage counts only excerpts in successful model calls; unretrieved excerpts have not completed semantic review."},
                {"本次未执行标准模板差异过滤，不能解读为只审查项目改动内容。", "本次未執行標準模板差異過濾，不能解讀為只審查項目改動內容。", "Standard-template difference filtering was not performed. This review cannot be interpreted as checking only project changes."},
                {"任务尚未完成规则和语义审查，当前覆盖仅反映源文件解析状态。", "任務尚未完成規則和語義審查，當前覆蓋僅反映源文件解析狀態。", "Rule and semantic review are not complete; current coverage reflects source parsing only."},
                {"规则已扫描全部可解析招标合同正文。", "規則已掃描全部可解析招標合同正文。", "Rules scanned all parseable tender contract body text."},
                {"标准文件仅作参考对照，规则未当作实际合同执行。", "標準文件僅作參考對照，規則未當作實際合同執行。", "Standards are comparison references; rules did not check them as actual contract obligations."},
                {"项目事实或文件目录不作为有效合同条款执行规则扫描。", "項目事實或文件目錄不作為有效合同條款執行規則掃描。", "Project facts and the package manifest are not rule-scanned as operative contract provisions."},
                {"规则和语义审查尚未完成。", "規則和語義審查尚未完成。", "Rule and semantic review are not complete."},
                {"资料类型：标准参考文件", "資料類型：標準參考文件", "Source role: Standard reference"},
                {"资料类型：招标合同条款", "資料類型：招標合同條款", "Source role: Tender provisions"},
                {"资料类型：项目事实资料", "資料類型：項目事實資料", "Source role: Project facts"},
                {"资料类型：文件目录及范围清单", "資料類型：文件目錄及範圍清單", "Source role: Package manifest and scope inventory"},
                {"规则检查对全部可解析招标合同正文运行。", "規則檢查對全部可解析招標合同正文執行。", "Rule checks run on all parseable tender contract body text."},
                {"规则检查对全部可解析正文运行。", "規則檢查對全部可解析正文執行。", "Rule checks run on all parseable body text."},
                {"语义审查未运行：本地 LLM 未启用或不可用；报告仅包含规则检查结果。", "語義審查未執行：本地 LLM 未啟用或不可用；報告僅包含規則檢查結果。", "Semantic review did not run: the local LLM is disabled or unavailable. This report contains rule-check results only."},
                {"评语、清单或问答参考被排除，避免答案泄漏。", "評語、清單或問答參考被排除，避免答案洩漏。", "Reference comments, checklists or questions and answers were excluded to prevent answer leakage."},
                {"文档解析不完整，未解析部分不纳入审查。", "文件解析不完整，未解析部分不納入審查。", "Document parsing is incomplete; unparsed portions are excluded from review."},
                {"解析覆盖记录无法读取，请重新解析。", "解析覆蓋記錄無法讀取，請重新解析。", "The parsing coverage record could not be read; reparse the source."},
                {"旧文件缺结构和覆盖记录，仅使用字符锚点，不推测Word页码。", "舊文件缺結構和覆蓋記錄，僅使用字元錨點，不推測 Word 頁碼。", "This legacy source lacks structure and coverage records. Only character anchors are used; Word page numbers are not inferred."},
                {"未完成解析的物理页：", "未完成解析的物理頁：", "Physical pages not fully parsed: "},
                {"没有可用的已解析正文：", "沒有可用的已解析正文：", "No usable parsed body text: "},
                {"DOCX locations are body/table positions; physical Word pages are unknown until rendering.", "DOCX 位置為正文／表格位置；渲染前 Word 物理頁碼未知。", "DOCX locations are body/table positions; physical Word pages are unknown until rendering."},
                {"Explicit run strike/dstrike and tracked deletions are retained; inherited style strike requires manual review.", "明確的文字刪除線／雙刪除線及追蹤刪除均保留；繼承樣式刪除線須人工覆核。", "Explicit run strike/dstrike and tracked deletions are retained; inherited style strike requires manual review."},
                {"OCR selection uses a per-page text/image heuristic; text accuracy and diagram topology require review.", "OCR 選擇採用逐頁文字／影像啟發式判斷；文字準確度及圖形結構須覆核。", "OCR selection uses a per-page text/image heuristic; text accuracy and diagram topology require review."}
        };
        for (String[] phrase : phrases) result = result.replace(phrase[0], tr(lang, phrase[0], phrase[1], phrase[2]));
        result = warningPattern(result, "主题 (\\d+) 局部比较窗口：预算略过 (\\d+) 组；引用目标或邻接限定未补齐的片段 (\\d+) 个。窗口不代表完整条款，未知限定须人工核对。",
                "主題 {1} 局部比較窗口：預算略過 {2} 組；引用目標或鄰接限定未補齊的片段 {3} 個。窗口不代表完整條款，未知限定須人工核對。",
                "Topic {1} local comparison window: {2} groups omitted by the budget; reference targets or adjacent qualifiers were not fully included for {3} chunks. The window is not a complete clause; unknown qualifiers require human review.", lang);
        result = warningPattern(result, "主题 (\\d+) 局部比较窗口：预算略过 (\\d+) 组、仅部分上下文提交 (\\d+) 组；引用目标或邻接限定未补齐的片段 (\\d+) 个。窗口不代表完整条款，未知限定须人工核对。",
                "主題 {1} 局部比較窗口：預算略過 {2} 組、僅部分上下文提交 {3} 組；引用目標或鄰接限定未補齊的片段 {4} 個。窗口不代表完整條款，未知限定須人工核對。",
                "Topic {1} local comparison window: {2} groups omitted by the budget, {3} groups submitted with partial context; reference targets or adjacent qualifiers were not fully included for {4} chunks. The window is not a complete clause; unknown qualifiers require human review.", lang);
        result = warningPattern(result, "语义仅覆盖 (\\d+)/(\\d+) 片段，其他片段未进入成功的模型调用。",
                "語義僅覆蓋 {1}/{2} 片段，其他片段未進入成功的模型呼叫。",
                "Semantic review covered only {1}/{2} excerpts; other excerpts were not included in successful model calls.", lang);
        result = warningPattern(result, "语义审查按 (\\d+) 个主题检索；已提交的是局部原文窗口，其他限定是否完整未知。未进入模型的切片不代表已经完成语义审查。",
                "語義審查按 {1} 個主題檢索；已提交的是局部原文窗口，其他限定是否完整未知。未進入模型的切片不代表已經完成語義審查。",
                "Semantic review retrieves by {1} topics. Submitted excerpts are local source windows; completeness of other qualifiers is unknown. Excerpts not submitted to the model have not completed semantic review.", lang);
        result = warningPattern(result, "语义审查按 (\\d+) 个主题检索，并计划 (\\d+) 组项目资料引用比较；已提交的是局部原文窗口，其他限定是否完整未知。未进入模型的切片不代表已经完成语义审查。",
                "語義審查按 {1} 個主題檢索，並計劃 {2} 組項目資料引用比較；已提交的是局部原文窗口，其他限定是否完整未知。未進入模型的切片不代表已經完成語義審查。",
                "Semantic review retrieves by {1} topics and plans {2} project-reference comparisons. Submitted excerpts are local source windows; completeness of other qualifiers is unknown. Excerpts not submitted to the model have not completed semantic review.", lang);
        result = warningPattern(result, "项目资料引用 ([^；\\r\\n]+) 未形成比较：(?:tender_target_not_located|未定位到对应招标条款)；不能推断条款缺失或不适用。",
                "項目資料引用 {1} 未形成比較：未定位對應招標條款；不能推斷條款缺失或不適用。",
                "Project reference {1} could not be compared because the tender provision was not located. This does not establish that the provision is missing or inapplicable.", lang);
        result = warningPattern(result, "项目资料引用 ([^；\\r\\n]+) 未形成比较：(?:ambiguous_tender_source_or_scope|对应招标来源或范围未确认)；不能推断条款缺失或不适用。",
                "項目資料引用 {1} 未形成比較：對應招標來源或範圍未確認；不能推斷條款缺失或不適用。",
                "Project reference {1} could not be compared because its tender source or scope is ambiguous. This does not establish that the provision is missing or inapplicable.", lang);
        result = warningPattern(result, "项目资料比较未提交：([^；\\r\\n]+)；预算或比较数量限制不代表已审查。",
                "項目資料比較未提交：{1}；預算或比較數量限制不代表已審查。",
                "Project-reference comparison not submitted: {1}. Budget or comparison limits do not establish completed review.", lang);
        result = warningPattern(result, "项目资料比较 ([^；\\r\\n]+) 未提交：超过本次比较数量限制；未提交材料不代表已审查。",
                "項目資料比較 {1} 未提交：超過本次比較數量限制；未提交材料不代表已審查。",
                "Project-reference comparison {1} was not submitted because the comparison limit was reached. Unsubmitted material has not completed review.", lang);
        result = warningPattern(result, "项目资料比较 ([^；\\r\\n]+) 未提交：完整招标条款及项目资料超过单次上下文预算；未提交材料不代表已审查。",
                "項目資料比較 {1} 未提交：完整招標條款及項目資料超過單次上下文預算；未提交材料不代表已審查。",
                "Project-reference comparison {1} was not submitted because the complete tender and project context exceeds the per-call budget. Unsubmitted material has not completed review.", lang);
        result = warningPattern(result, "主题 (\\d+) 的一条模型发现未通过逐字证据校验，已剔除。",
                "主題 {1} 的一條模型發現未通過逐字證據校驗，已剔除。",
                "A model finding for topic {1} failed exact source-quotation validation and was excluded.", lang);
        result = warningPattern(result, "主题 (\\d+) 的一条模型记录未通过证据或适用性校验，未采纳。",
                "主題 {1} 的一條模型記錄未通過證據或適用性校驗，未採納。",
                "A model record for topic {1} failed evidence or applicability validation and was not accepted.", lang);
        result = warningPattern(result, "主题 (\\d+) 未完成语义检查：", "主題 {1} 未完成語義檢查：", "Topic {1} did not complete semantic review: ", lang);
        result = warningPattern(result, "主题 (\\d+) 检索失败，使用词项检索：", "主題 {1} 檢索失敗，使用詞項檢索：", "Retrieval failed for topic {1}; lexical retrieval was used: ", lang);
        result = warningPattern(result, "主题 (\\d+) 的 (standard|project_fact|package_manifest) 检索失败：", "主題 {1} 的 {2} 檢索失敗：", "Retrieval of {2} context failed for topic {1}: ", lang);
        result = warningPattern(result, "主观风险按严重程度排序后限制为 (\\d+) 条，另有 (\\d+) 条未列入。",
                "主觀風險按嚴重程度排序後限制為 {1} 條，另有 {2} 條未列入。",
                "Subjective risks were sorted by severity and limited to {1} findings; {2} additional findings were omitted.", lang);
        result = warningPattern(result, "无法连接 ([^，\\r\\n]+)，请确认本地服务已启动（([^\\r\\n]+)）",
                "無法連接 {1}，請確認本地服務已啟動（{2}）", "Cannot connect to {1}; confirm that the local service has started ({2})", lang);
        return result;
    }
    private String warningPattern(String value, String pattern, String hant, String en, String lang) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = tr(lang, matcher.group(), hant, en);
            for (int i = 1; i <= matcher.groupCount(); i++) replacement = replacement.replace("{" + i + "}", matcher.group(i));
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
    private String available(String value, String lang) { return nvl(value).trim().isEmpty() ? tr(lang, "未提供", "未提供", "Not available") : value; }
    private static String nvl(String value) { return value == null ? "" : value; }
    private static <T> List<T> safe(List<T> value) { return value == null ? Collections.emptyList() : value; }
    private static String tr(String lang, String hans, String hant, String en) { return "en".equalsIgnoreCase(lang) ? en : "zh-Hant".equalsIgnoreCase(lang) ? hant : hans; }
}
