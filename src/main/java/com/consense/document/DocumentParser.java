package com.consense.document;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFFootnote;
import org.apache.poi.xwpf.usermodel.XWPFEndnote;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.w3c.dom.Node;
import org.w3c.dom.NamedNodeMap;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.mail.BodyPart;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 多格式文档解析：
 *  PDF 优先取文本层，文本层不足（扫描件）时按页渲染转 OCR；
 *  DOCX / DOC / MSG / EML / TXT / MD 各自解析为按页或整段的纯文本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParser {

    private static final long MAX_ZIP_ENTRY_BYTES = 128L * 1024 * 1024;
    private static final long MAX_ZIP_XML_BYTES = 32L * 1024 * 1024;
    private static final long MAX_ZIP_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 5000;

    static {
        // Legitimate tender EMF images compress below POI's default 1% ratio.
        // Configure the process-wide POI guard once, never around individual requests.
        ZipSecureFile.setMinInflateRatio(0.001);
        ZipSecureFile.setMaxEntrySize(MAX_ZIP_ENTRY_BYTES);
        ZipSecureFile.setMaxTextSize(MAX_ZIP_XML_BYTES);
    }

    private final ConsenseProperties props;
    private final OcrClient ocrClient;

    public ParsedDocument parse(String fileName, byte[] bytes) {
        return parse(fileName,bytes,DocumentParseProbe.disabled());
    }

    /** Vetting may opt into a fresh job linked to its actual stored source and original bytes. */
    public ParsedDocument parse(String fileName,byte[] bytes,String parseJobId,String sourceId,String expectedSourceSha256) {
        DocumentParseProbe probe=DocumentParseProbe.start(props,parseJobId,sourceId,expectedSourceSha256,fileName,bytes);
        return parse(fileName,bytes,probe);
    }
    private ParsedDocument parse(String fileName,byte[] bytes,DocumentParseProbe probe) {
        boolean returnedNormally=false;Throwable operationFailure=null;
        String ext = extensionOf(fileName);
        try (DocumentParseProbe.Timer timer=probe.measure("parse_dispatch_and_document",null,"Actual parser operation; measured probe event I/O excluded, nested stages are not additive")) {
            ParsedDocument result;
            if ("pdf".equals(ext)) {
                result=parsePdf(bytes,probe);
            } else if ("docx".equals(ext)) result=parseDocx(bytes,probe);
            else if ("doc".equals(ext)) result=parseDoc(bytes);
            else if ("msg".equals(ext)) result=parseMsg(bytes);
            else if ("eml".equals(ext) || "emlx".equals(ext)) result=parseEml(bytes);
            else result=parsePlainText(bytes);
            if(probe.enabled())probe.event("parse_output",null,DocumentParseProbe.map("parseStatus",result.getParseStatus(),"result",result,
                    "textUtf16Chars",result.getText().length(),"textUtf8Sha256",DocumentParseProbe.sha256(result.getText().getBytes(StandardCharsets.UTF_8)),
                    "boundary","Exact parser output and deletion/strike metadata; source effectiveness and OCR accuracy remain unverified"));
            returnedNormally=true;return result;
        } catch(DocumentParseProbe.Failure observation) {operationFailure=observation;throw observation;
        } catch (BizException e) {
            operationFailure=e;probe.originalFailure("parse_failure",null,e);
            throw e;
        } catch (Exception e) {
            probe.originalFailure("parse_failure",null,e);
            log.warn("解析 {} 失败: {}", fileName, e.getMessage());
            BizException wrapped=new BizException(4003, "无法解析文件 " + fileName + "：" + e.getMessage());operationFailure=wrapped;throw wrapped;
        } catch(Error e){operationFailure=e;probe.originalFailure("parse_failure",null,e);throw e;
        } finally {probe.finish(returnedNormally?"returned_normally":"aborted",operationFailure);}
    }

    // ------------------------------------------------------------------ PDF

    private ParsedDocument parsePdf(byte[] bytes,DocumentParseProbe probe) throws Exception {
        try (PDDocument document = PDDocument.load(bytes)) {
            int totalPages = document.getNumberOfPages();
            List<PageText> pages = new ArrayList<>(totalPages);
            List<DocumentBlock> blocks = new ArrayList<>();
            ParseCoverage coverage = new ParseCoverage();
            coverage.setTotalPages(totalPages);
            PDFRenderer renderer = new PDFRenderer(document);
            Boolean ocrReady = null;
            for (int pageNo = 1; pageNo <= totalPages; pageNo++) {
                PDPage page = document.getPage(pageNo - 1);
                PositionedStripper stripper = new PositionedStripper(pageNo, page);
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String text;
                List<DocumentBlock> pageBlocks;
                boolean requiresOcr;
                try (DocumentParseProbe.Timer timer=probe.measure("pdf_native_extraction_and_heuristic",pageNo,"Actual native extraction and original short-circuit OCR selection")) {
                    String nativeExtractedText=stripper.getText(document);
                    text = normalize(nativeExtractedText);
                    pageBlocks = stripper.blocks;
                    requiresOcr = needsOcr(text, page,probe,pageNo);
                    probe.event("pdf_native_extraction",pageNo,DocumentParseProbe.map("nativeExtractedText",nativeExtractedText,"normalizedText",text,"blocks",pageBlocks,"requiresOcr",requiresOcr,
                            "pageRotation",page.getRotation(),"cropBox",page.getCropBox().toString()));
                } catch (Exception e) {
                    if(e instanceof DocumentParseProbe.Failure)throw e;
                    probe.event("pdf_native_extraction_failure",pageNo,DocumentParseProbe.map("failureChain",DocumentParseProbe.failures(e),"nativeBlocksBeforeDiscard",stripper.blocks));
                    text = "";
                    pageBlocks = new ArrayList<>();
                    requiresOcr = true;
                    coverage.getLimitations().add("Page " + pageNo + " native extraction failed: "
                            + abbreviate(e.getMessage(), 120));
                }
                String status = "native";
                if (requiresOcr) {
                    try {
                        BufferedImage image;
                        try(DocumentParseProbe.Timer timer=probe.measure("pdf_render",pageNo,"Original PDFRenderer RGB render at fixed 200 DPI")) {
                            image = renderer.renderImageWithDPI(pageNo - 1, 200, ImageType.RGB);
                        }
                        probe.image("pdf_render_image",pageNo,image,200);
                        // An empty text layer alone does not prove an empty physical page:
                        // scans and vector artwork can both lack text and image resources.
                        boolean white=false;
                        if(text.isEmpty())try(DocumentParseProbe.Timer timer=probe.measure("pdf_blank_page_test",pageNo,"Original exact all-white pixel test, called only for empty native text")) {white=isWhitePage(image);}
                        probe.event("pdf_blank_page_predicate",pageNo,DocumentParseProbe.map("nativeTextEmpty",text.isEmpty(),"whiteTestEvaluated",text.isEmpty(),"allWhite",text.isEmpty()?white:null));
                        if (text.isEmpty() && white) {
                            coverage.getBlankPages().add(pageNo);
                            status = "blank";
                        } else {
                            OcrClient actualOcr=probe.enabled()?new ObservedOcrClient(ocrClient,probe,pageNo):ocrClient;
                            if (ocrReady == null) ocrReady = props.getOcr().isEnabled() && actualOcr.available();
                            probe.event("pdf_ocr_routing",pageNo,DocumentParseProbe.map("configuredEnabled",props.getOcr().isEnabled(),"ocrReady",ocrReady,
                                    "boundary","Availability remains the original once-per-document cached decision; no extra health check"));
                            if (!ocrReady) throw new IOException("OCR disabled or service unavailable");
                            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                            try(DocumentParseProbe.Timer timer=probe.measure("pdf_ocr_input_png_encoding",pageNo,"Original PNG encoding used as actual OCR input")) {ImageIO.write(image, "png", buffer);}
                            OcrClient.OcrResult result = actualOcr.recognize(buffer.toByteArray());
                            if (result == null || result.isEmpty()) throw new IOException("OCR returned no text");
                            text = normalize(result.getText());
                            pageBlocks = ocrBlocks(result, pageNo, image.getWidth(), image.getHeight());
                            coverage.setOcrPages(coverage.getOcrPages() + 1);
                            coverage.setOcrQualityStatus("needs_review");
                            coverage.getNeedsReviewPages().add(pageNo);
                            status = "ocr";
                        }
                    } catch (Exception e) {
                        if(e instanceof DocumentParseProbe.Failure)throw e;
                        probe.event("pdf_ocr_page_failure",pageNo,DocumentParseProbe.map("failureChain",DocumentParseProbe.failures(e)));
                        coverage.getFailedPages().add(pageNo);
                        coverage.getLimitations().add("Page " + pageNo + ": " + abbreviate(e.getMessage(), 180));
                        status = "ocr_failed";
                        log.warn("第 {} 页 OCR 未完成: {}", pageNo, e.getMessage());
                    }
                }
                if (!"ocr_failed".equals(status)) {
                    coverage.setParsedPages(coverage.getParsedPages() + 1);
                }
                if (pageBlocks.isEmpty() && !JsonUtils.isBlankText(text)) {
                    pageBlocks.add(block("pdf_page", "pdf-page/" + pageNo, pageNo, text, status));
                }
                pages.add(new PageText(pageNo, text, status));
                blocks.addAll(pageBlocks);
                probe.event("pdf_physical_page_output",pageNo,DocumentParseProbe.map("status",status,"text",text,"blocks",pageBlocks,
                        "pageParsed",!"ocr_failed".equals(status),"coverageSoFar",coverage));
            }
            String text = joinPages(pages);
            coverage.setComplete(coverage.getFailedPages().isEmpty());
            coverage.getLimitations().add("OCR selection uses a per-page text/image heuristic; text accuracy and diagram topology require review.");
            String message = "PDF 共 " + totalPages + " 页，已解析 " + coverage.getParsedPages()
                    + " 页，OCR " + coverage.getOcrPages() + " 页，确认空白 " + coverage.getBlankPages().size() + " 页"
                    + (coverage.isComplete() ? "" : "；未完成页 " + coverage.getFailedPages())
                    + (coverage.getOcrPages() > 0 ? "；OCR 文字质量待原页核对 " + coverage.getNeedsReviewPages() : "");
            return new ParsedDocument(text, pages, totalPages, coverage.getOcrPages() > 0,
                    message, blocks, coverage);
        }
    }

    private static boolean isWhitePage(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) return false;
        }
        return true;
    }

    private boolean needsOcr(String text, PDPage page,DocumentParseProbe probe,int physicalPage) throws IOException {
        int visible = text.replaceAll("\\s", "").length();
        int bad = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD' || text.charAt(i) == '\u0000') bad++;
        }
        boolean shortText=visible < props.getOcr().getTextLayerMinChars();
        boolean corrupt=bad > Math.max(3,visible/10);
        // Preserve the original boolean short circuit and resource traversal exactly.
        boolean imageEvaluated=!shortText&&!corrupt&&visible<200;
        Boolean substantial=imageEvaluated?hasSubstantialImage(page.getResources(),0,probe,physicalPage):null;
        boolean selected=shortText||corrupt||(visible<200&&Boolean.TRUE.equals(substantial));
        probe.event("pdf_ocr_selection_predicate",physicalPage,DocumentParseProbe.map("visibleChars",visible,"badChars",bad,
                "minimumTextChars",props.getOcr().getTextLayerMinChars(),"shortText",shortText,"corruptText",corrupt,
                "imagePredicateEvaluated",imageEvaluated,"hasSubstantialImage",substantial,"imageWidthMin",400,"imageHeightMin",200,
                "imagePredicateDepthLimit",4,"requiresOcr",selected));
        return selected;
    }

    private boolean hasSubstantialImage(PDResources resources, int depth,DocumentParseProbe probe,int physicalPage) throws IOException {
        if (resources == null || depth > 4) return false;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject) {
                PDImageXObject image = (PDImageXObject) object;
                probe.event("pdf_image_resource_predicate",physicalPage,DocumentParseProbe.map("resourceName",name.getName(),"formDepth",depth,
                        "width",image.getWidth(),"height",image.getHeight(),"substantial",image.getWidth()>=400&&image.getHeight()>=200));
                if (image.getWidth() >= 400 && image.getHeight() >= 200) return true;
            } else if (object instanceof PDFormXObject
                    && hasSubstantialImage(((PDFormXObject) object).getResources(), depth + 1,probe,physicalPage)) {
                return true;
            }
        }
        return false;
    }

    private List<DocumentBlock> ocrBlocks(OcrClient.OcrResult result, int page, int width, int height) {
        List<DocumentBlock> blocks = new ArrayList<>();
        int index = 0;
        for (OcrClient.OcrLine line : result.getLines()) {
            DocumentBlock block = block("ocr_line", "pdf-page/" + page + "/ocr-line/" + index++, page,
                    normalize(line.getText()), "ocr");
            block.setConfidence(line.getConfidence());
            block.setBbox(normalizedBox(line.getBbox(), width, height));
            blocks.add(block);
        }
        if (blocks.isEmpty()) {
            DocumentBlock block = block("ocr_page", "pdf-page/" + page, page, result.getText(), "ocr");
            block.setConfidence(result.getConfidence());
            blocks.add(block);
        }
        return blocks;
    }

    // ----------------------------------------------------------------- DOCX

    private ParsedDocument parseDocx(byte[] bytes,DocumentParseProbe probe) throws Exception {
        try(DocumentParseProbe.Timer timer=probe.measure("docx_zip_preflight",null,"Original bounded OOXML inflation guard")){preflightDocx(bytes);}
        List<DocumentBlock> blocks = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            DocxNumberingResolver numbering = new DocxNumberingResolver(document);
            int bodyIndex = 0;
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    XWPFParagraph paragraph = (XWPFParagraph) element;
                    DocumentBlock block = wordBlock(paragraph.getCTP().getDomNode(), "paragraph",
                            "body/" + bodyIndex + "/paragraph", numbering);
                    block.setParagraphStyle(paragraph.getStyle());
                    if (paragraph.getStyle() != null && document.getStyles() != null) {
                        XWPFStyle style = document.getStyles().getStyle(paragraph.getStyle());
                        if (style != null) block.setParagraphStyleName(style.getName());
                    }
                    if (hasContent(block)) blocks.add(block);
                } else if (element instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) element;
                    int rowIndex = 0;
                    for (XWPFTableRow row : table.getRows()) {
                        String location = "body/" + bodyIndex + "/table-row/" + rowIndex++;
                        WordText rowText = new WordText();
                        List<String> values = new ArrayList<>();
                        int cellIndex = 0;
                        for (XWPFTableCell cell : row.getTableCells()) {
                            WordText value = new WordText();
                            collectWordText(cell.getCTTc().getDomNode(), false, false, value, numbering,
                                    location + "/cell/" + cellIndex++);
                            values.add(normalize(value.effective.toString()));
                            rowText.add(value);
                        }
                        DocumentBlock block = wordBlock(rowText, "table_row", location);
                        block.setCells(values);
                        block.setText(String.join(" | ", values));
                        if (hasContent(block)) blocks.add(block);
                    }
                }
                bodyIndex++;
            }
            int index = 0;
            for (XWPFHeader header : document.getHeaderList()) {
                DocumentBlock block = wordBlock(header._getHdrFtr().getDomNode(), "header", "header/" + index++, numbering.newStory());
                if (hasContent(block)) blocks.add(block);
            }
            index = 0;
            for (XWPFFooter footer : document.getFooterList()) {
                DocumentBlock block = wordBlock(footer._getHdrFtr().getDomNode(), "footer", "footer/" + index++, numbering.newStory());
                if (hasContent(block)) blocks.add(block);
            }
            DocxNumberingResolver footnoteNumbering = numbering.newStory();
            for (XWPFFootnote note : document.getFootnotes()) {
                // OOXML reserves IDs -1 and 0 for separator notes, which are not contract provisions.
                if (note.getId().signum() <= 0) continue;
                DocumentBlock block = wordBlock(note.getCTFtnEdn().getDomNode(), "footnote",
                        "word-footnote/" + note.getId(), footnoteNumbering);
                if (hasContent(block)) blocks.add(block);
            }
            DocxNumberingResolver endnoteNumbering = numbering.newStory();
            for (XWPFEndnote note : document.getEndnotes()) {
                if (note.getId().signum() <= 0) continue;
                DocumentBlock block = wordBlock(note.getCTFtnEdn().getDomNode(), "endnote",
                        "word-endnote/" + note.getId(), endnoteNumbering);
                if (hasContent(block)) blocks.add(block);
            }
            ParseCoverage coverage = unpaginatedCoverage("DOCX locations are body/table positions; physical Word pages are unknown until rendering.");
            coverage.getLimitations().add("Explicit run strike/dstrike and tracked deletions are retained; inherited style strike requires manual review.");
            int unresolvedNumbering = 0, unresolvedSymbols = 0, structuralWarnings = 0;
            for (DocumentBlock block : blocks) {
                structuralWarnings += block.getWordWarnings().size();
                for (DocumentBlock.WordNumbering item : block.getWordNumbering())
                    if (!"resolved".equals(item.getResolutionStatus())) unresolvedNumbering++;
                for (DocumentBlock.WordSymbol item : block.getWordSymbols())
                    if (!"resolved".equals(item.getResolutionStatus())) unresolvedSymbols++;
            }
            if (unresolvedNumbering + unresolvedSymbols + structuralWarnings > 0) {
                coverage.setComplete(false);
                coverage.getLimitations().add("Unresolved Word numbering: " + unresolvedNumbering
                        + "; unresolved font symbols: " + unresolvedSymbols + "; structural warnings: " + structuralWarnings
                        + ". Original properties and locations are retained; unresolved values must not be treated as absent.");
            }
            if(probe.enabled())for(DocumentBlock observed:blocks)probe.event("docx_source_block",null,DocumentParseProbe.map("block",observed,
                    "blockJsonUtf8Sha256",DocumentParseProbe.sha256(JsonUtils.write(observed).getBytes(StandardCharsets.UTF_8)),
                    "physicalWordPage",null,"boundary","Exact original body/table/header/footer/note output; deleted and strike text retained independently"));
            return new ParsedDocument(joinBlocks(blocks), Collections.emptyList(), 0, false,
                    "DOCX 已解析 " + blocks.size() + " 个段落/表格/页眉页脚/脚注尾注位置；未推算页码", blocks, coverage);
        }
    }

    /** Bound actual inflation before POI materializes OOXML; ZIP declared sizes are untrusted. */
    private void preflightDocx(byte[] bytes) throws IOException {
        ConsenseProperties.Document cfg = props.getDocument();
        long entryLimit = positiveLimit(cfg.getMaxZipEntryBytes(), MAX_ZIP_ENTRY_BYTES);
        long xmlLimit = positiveLimit(cfg.getMaxZipXmlBytes(), MAX_ZIP_XML_BYTES);
        long totalLimit = positiveLimit(cfg.getMaxZipTotalBytes(), MAX_ZIP_TOTAL_BYTES);
        int countLimit = (int) positiveLimit(cfg.getMaxZipEntries(), MAX_ZIP_ENTRIES);
        long total = 0;
        int count = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++count > countLimit) {
                    throw new IOException("DOCX ZIP entry count exceeds " + countLimit);
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                boolean xml = name.endsWith(".xml") || name.endsWith(".rels");
                long expanded = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    expanded += read;
                    total += read;
                    if (expanded > entryLimit) {
                        throw new IOException("DOCX ZIP entry expanded bytes exceed " + entryLimit
                                + ": " + entry.getName());
                    }
                    if (xml && expanded > xmlLimit) {
                        throw new IOException("DOCX ZIP XML expanded bytes exceed " + xmlLimit
                                + ": " + entry.getName());
                    }
                    if (total > totalLimit) {
                        throw new IOException("DOCX ZIP total expanded bytes exceed " + totalLimit);
                    }
                }
                input.closeEntry();
            }
        }
    }

    private static long positiveLimit(long configured, long ceiling) throws IOException {
        if (configured <= 0) throw new IOException("DOCX ZIP limits must be positive");
        return Math.min(configured, ceiling);
    }

    // ------------------------------------------------------------------ DOC

    private ParsedDocument parseDoc(byte[] bytes) throws Exception {
        String text;
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(document)) {
            text = normalize(extractor.getText());
        }
        return unpaginated(text, "doc", "DOC 已解析；未渲染页码", "Legacy DOC text has no rendered page or run-deletion mapping.");
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
        return unpaginated(text, "email", "MSG 邮件解析完成", "Email body positions have no physical pages.");
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
        return unpaginated(text, "email", "EML 邮件解析完成", "Email body positions have no physical pages.");
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
        return unpaginated(text, "text", "纯文本解析完成", "Text positions have no physical pages.");
    }

    // -------------------------------------------------------------- helpers

    private ParsedDocument unpaginated(String text, String kind, String message, String limitation) {
        List<DocumentBlock> blocks = new ArrayList<>();
        String[] paragraphs = text.split("\\n{2,}");
        for (int i = 0; i < paragraphs.length; i++) {
            if (!JsonUtils.isBlankText(paragraphs[i])) {
                blocks.add(block(kind, "body/" + i, null, normalize(paragraphs[i]), kind));
            }
        }
        return new ParsedDocument(text, Collections.emptyList(), 0, false, message,
                blocks, unpaginatedCoverage(limitation));
    }

    private ParseCoverage unpaginatedCoverage(String limitation) {
        ParseCoverage coverage = new ParseCoverage();
        coverage.setComplete(true);
        coverage.getLimitations().add(limitation);
        return coverage;
    }

    private DocumentBlock block(String kind, String location, Integer page, String text, String source) {
        DocumentBlock block = new DocumentBlock();
        block.setId(location.replace('/', ':'));
        block.setKind(kind);
        block.setLocation(location);
        block.setPageNo(page);
        block.setText(normalize(text));
        block.setOriginalText(normalize(text));
        block.setSource(source);
        return block;
    }

    private DocumentBlock wordBlock(Node node, String kind, String location, DocxNumberingResolver numbering) {
        WordText text = new WordText();
        collectWordText(node, false, false, text, numbering, location);
        return wordBlock(text, kind, location);
    }

    private DocumentBlock wordBlock(WordText text, String kind, String location) {
        DocumentBlock block = block(kind, location, null, text.effective.toString(), "docx");
        block.setOriginalText(normalize(text.original.toString()));
        block.setDeletedText(normalize(text.deleted.toString()));
        block.setStrikeText(normalize(text.struck.toString()));
        block.setWordStructureVersion("docx-numbering-symbol-v1");
        block.setWordNumbering(text.numbering);
        block.setWordSymbols(text.symbols);
        block.setWordWarnings(text.warnings);
        return block;
    }

    private boolean hasContent(DocumentBlock block) {
        return !JsonUtils.isBlankText(block.getOriginalText()) || !block.getWordNumbering().isEmpty()
                || !block.getWordSymbols().isEmpty() || !block.getWordWarnings().isEmpty();
    }

    /** Traverse OOXML so deleted runs do not enter the effective contract text. */
    private void collectWordText(Node node, boolean deleted, boolean struck, WordText out,
                                 DocxNumberingResolver numbering, String location) {
        String name = node.getLocalName();
        boolean isDeleted = deleted || "del".equals(name) || "moveFrom".equals(name);
        boolean isStruck = struck;
        if ("txbxContent".equals(name)) {
            numbering = numbering.newStory();
        }
        if ("AlternateContent".equals(name)) {
            WordText alternative = new WordText();
            int alternativeIndex = 0;
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                collectWordText(child, isDeleted, isStruck, alternative, numbering.newStory(),
                        location + "/alternative/" + alternativeIndex++);
            }
            alternative.warnings.add("Unrendered OOXML AlternateContent branches at " + location);
            for (DocumentBlock.WordNumbering item : alternative.numbering) {
                item.setResolutionStatus("unresolved"); item.setReason("alternate_content_branch_not_selected");
            }
            if (!JsonUtils.isBlankText(alternative.effective.toString()))
                alternative.effective.insert(0, "[unresolved Word AlternateContent] ");
            out.add(alternative);
            return;
        }
        if ("p".equals(name)) {
            DocxNumberingResolver.NumberingInfo resolved = numbering.resolve(node);
            WordText paragraph = new WordText();
            int childIndex = 0;
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                collectWordText(child, isDeleted, isStruck, paragraph, numbering, location + "/node/" + childIndex++);
            }
            if (resolved != null && resolved.isNumbered()) {
                // Note parts are enumerated by storage order. Cross-note label order
                // cannot be asserted without following/rendering body references.
                boolean trusted = resolved.isResolved() && !location.startsWith("word-footnote/")
                        && !location.startsWith("word-endnote/");
                boolean active = !JsonUtils.isBlankText(paragraph.effective.toString());
                DocumentBlock.WordNumbering metadata = new DocumentBlock.WordNumbering();
                metadata.setLocation(location); metadata.setNumId(resolved.getNumId());
                metadata.setAbstractNumId(resolved.getAbstractNumId()); metadata.setLevel(resolved.getLevel());
                metadata.setFormat(resolved.getFormat()); metadata.setLevelText(resolved.getLevelText());
                metadata.setSuffix(resolved.getSuffix());
                metadata.setLabel(resolved.getLabel()); metadata.setSource(resolved.getSource());
                metadata.setValue(resolved.getValue()); metadata.setReason(trusted ? resolved.getReason()
                        : resolved.isResolved() ? "note_reference_order_not_verified" : resolved.getReason());
                metadata.setResolutionStatus(trusted ? "resolved" : "unresolved");
                metadata.setAppliedToEffectiveText(active); paragraph.numbering.add(metadata);
                String label = (trusted ? resolved.getLabel() : "[unresolved Word numbering]")
                        + (trusted && "nothing".equals(resolved.getSuffix()) ? "" : " ");
                if (!JsonUtils.isBlankText(paragraph.original.toString())) paragraph.original.insert(0, label);
                if (active) paragraph.effective.insert(0, label);
                else {
                    if (!JsonUtils.isBlankText(paragraph.deleted.toString())) paragraph.deleted.insert(0, label);
                    if (!JsonUtils.isBlankText(paragraph.struck.toString())) paragraph.struck.insert(0, label);
                }
            }
            appendWordText(paragraph, "\n", isDeleted, isStruck);
            out.add(paragraph);
            return;
        }
        if ("r".equals(name)) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!"rPr".equals(child.getLocalName())) continue;
                for (Node property = child.getFirstChild(); property != null; property = property.getNextSibling()) {
                    if (("strike".equals(property.getLocalName()) || "dstrike".equals(property.getLocalName()))
                            && onOff(property)) isStruck = true;
                }
            }
        }
        if ("t".equals(name) || "delText".equals(name)) {
            appendWordText(out, nodeText(node), isDeleted || "delText".equals(name), isStruck);
            return;
        }
        if ("sym".equals(name)) {
            WordSymbolDecoder.Symbol decoded = WordSymbolDecoder.decode(node);
            DocumentBlock.WordSymbol metadata = new DocumentBlock.WordSymbol();
            metadata.setLocation(location); metadata.setFont(decoded.getFont()); metadata.setHexCode(decoded.getHexCode());
            metadata.setDecodedText(decoded.getDecodedText()); metadata.setResolutionStatus(decoded.getResolutionStatus());
            metadata.setMappingSource(decoded.getMappingSource()); metadata.setDeleted(isDeleted); metadata.setStruck(isStruck);
            out.symbols.add(metadata);
            appendWordText(out, decoded.isResolved() ? decoded.getDecodedText() : "[unresolved Word symbol]", isDeleted, isStruck);
            return;
        }
        if ("tab".equals(name)) appendWordText(out, " ", isDeleted, isStruck);
        if ("br".equals(name) || "cr".equals(name)) appendWordText(out, "\n", isDeleted, isStruck);
        int childIndex = 0;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectWordText(child, isDeleted, isStruck, out, numbering, location + "/node/" + childIndex++);
        }
    }

    private void appendWordText(WordText out, String text, boolean deleted, boolean struck) {
        out.original.append(text);
        if (deleted) out.deleted.append(text);
        if (struck) out.struck.append(text);
        if (!deleted && !struck) out.effective.append(text);
    }

    private String nodeText(Node node) {
        StringBuilder text = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            } else {
                text.append(nodeText(child));
            }
        }
        return text.toString();
    }

    private boolean onOff(Node property) {
        NamedNodeMap attrs = property.getAttributes();
        Node value = attrs == null ? null : attrs.getNamedItemNS(
                "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val");
        String text = value == null ? "true" : value.getNodeValue();
        return !("0".equals(text) || "false".equalsIgnoreCase(text) || "off".equalsIgnoreCase(text));
    }

    private String joinBlocks(List<DocumentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (DocumentBlock block : blocks) {
            if (!JsonUtils.isBlankText(block.getText())) text.append(block.getText()).append("\n\n");
        }
        return normalize(text.toString());
    }

    private static double[] normalizedBox(double[] box, double width, double height) {
        if (box == null || box.length != 4 || width <= 0 || height <= 0) return null;
        for (double value : box) if (!Double.isFinite(value)) return null;
        double x = Math.max(0, Math.min(1, box[0] / width));
        double y = Math.max(0, Math.min(1, box[1] / height));
        return new double[]{x, y, Math.max(0, Math.min(1 - x, box[2] / width)),
                Math.max(0, Math.min(1 - y, box[3] / height))};
    }

    private static String abbreviate(String text, int limit) {
        if (text == null) return "unknown failure";
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static final class WordText {
        private final StringBuilder effective = new StringBuilder();
        private final StringBuilder original = new StringBuilder();
        private final StringBuilder deleted = new StringBuilder();
        private final StringBuilder struck = new StringBuilder();
        private final List<DocumentBlock.WordNumbering> numbering = new ArrayList<>();
        private final List<DocumentBlock.WordSymbol> symbols = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private void add(WordText other) {
            effective.append(other.effective); original.append(other.original);
            deleted.append(other.deleted); struck.append(other.struck);
            numbering.addAll(other.numbering); symbols.addAll(other.symbols);
            warnings.addAll(other.warnings);
        }
    }

    private final class PositionedStripper extends PDFTextStripper {
        private final List<DocumentBlock> blocks = new ArrayList<>();
        private final int pageNo;
        private final int pageRotation;

        private PositionedStripper(int pageNo, PDPage page) throws IOException {
            this.pageNo = pageNo;
            pageRotation = page.getRotation();
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (!JsonUtils.isBlankText(text)) {
                DocumentBlock block = block("pdf_line", "pdf-page/" + pageNo + "/line/" + blocks.size(),
                        pageNo, text, "native");
                if (!positions.isEmpty()) {
                    double x = Double.POSITIVE_INFINITY, y = Double.POSITIVE_INFINITY;
                    double right = Double.NEGATIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
                    for (TextPosition position : positions) {
                        // Direction-adjusted text coordinates must be mapped back to the rendered page.
                        int direction = Math.round(position.getDir());
                        boolean quarterTurn = direction % 180 != 0;
                        double frameWidth = quarterTurn ? position.getPageHeight() : position.getPageWidth();
                        double frameHeight = quarterTurn ? position.getPageWidth() : position.getPageHeight();
                        double[] glyph = normalizedBox(new double[]{position.getXDirAdj(),
                                position.getYDirAdj() - position.getHeightDir(),
                                position.getWidthDirAdj(), position.getHeightDir()}, frameWidth, frameHeight);
                        if (glyph == null) continue;
                        glyph = rotateBox(glyph, pageRotation - direction);
                        x = Math.min(x, glyph[0]);
                        y = Math.min(y, glyph[1]);
                        right = Math.max(right, glyph[0] + glyph[2]);
                        bottom = Math.max(bottom, glyph[1] + glyph[3]);
                    }
                    block.setBbox(normalizedBox(new double[]{x, y, right - x, bottom - y}, 1, 1));
                }
                blocks.add(block);
            }
            super.writeString(text, positions);
        }
    }

    private static double[] rotateBox(double[] b, int rotation) {
        switch (((rotation % 360) + 360) % 360) {
            case 90: return new double[]{1 - b[1] - b[3], b[0], b[3], b[2]};
            case 180: return new double[]{1 - b[0] - b[2], 1 - b[1] - b[3], b[2], b[3]};
            case 270: return new double[]{b[1], 1 - b[0] - b[2], b[3], b[2]};
            default: return b;
        }
    }

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
        private final List<DocumentBlock> blocks;
        private final ParseCoverage coverage;

        public ParsedDocument(String text, List<PageText> pages, int pageCount,
                              boolean ocrUsed, String message) {
            this(text, pages, pageCount, ocrUsed, message, Collections.emptyList(), legacyCoverage(pageCount));
        }

        public ParsedDocument(String text, List<PageText> pages, int pageCount,
                              boolean ocrUsed, String message, List<DocumentBlock> blocks, ParseCoverage coverage) {
            this.text = text;
            this.pages = pages;
            this.pageCount = pageCount;
            this.ocrUsed = ocrUsed;
            this.message = message;
            this.blocks = blocks;
            this.coverage = coverage;
            markUnverifiedOcrQuality();
            if (JsonUtils.isBlankText(text) && !allPagesConfirmedBlank()) this.coverage.setComplete(false);
        }

        private void markUnverifiedOcrQuality() {
            // Preserve extracted text and physical completion. Page identities come only from
            // actual OCR page declarations, never from an OCR flag, confidence or source role.
            if (coverage.getNeedsReviewPages() == null) coverage.setNeedsReviewPages(new ArrayList<>());
            boolean declaredOcrPage = false;
            boolean invalidOcrPageIdentity = false;
            if (pages != null) for (PageText page : pages) {
                if (page != null && "ocr".equals(page.getStatus())) {
                    declaredOcrPage = true;
                    if (page.getPageNo() <= 0 || (pageCount > 0 && page.getPageNo() > pageCount)) {
                        invalidOcrPageIdentity = true;
                    } else if (!coverage.getNeedsReviewPages().contains(page.getPageNo())) {
                        coverage.getNeedsReviewPages().add(page.getPageNo());
                    }
                }
            }
            if (ocrUsed || coverage.getOcrPages() > 0 || declaredOcrPage) {
                coverage.setOcrQualityStatus("needs_review");
                coverage.setOcrQualityPageScopeUnknown(coverage.getNeedsReviewPages().isEmpty()
                        || coverage.getOcrPages() > coverage.getNeedsReviewPages().size() || invalidOcrPageIdentity);
                String limitation = "OCR text quality is independently unverified; original-page review is required for text, footnotes, numbering and blank-field relationships. Physical extraction completion is not quality confirmation.";
                if (!coverage.getLimitations().contains(limitation)) coverage.getLimitations().add(limitation);
            }
        }

        private static ParseCoverage legacyCoverage(int pageCount) {
            ParseCoverage coverage = new ParseCoverage();
            coverage.setTotalPages(pageCount);
            coverage.setParsedPages(pageCount);
            coverage.setComplete(true);
            return coverage;
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

        public List<DocumentBlock> getBlocks() { return blocks; }

        public ParseCoverage getCoverage() { return coverage; }

        public String getParseStatus() {
            if (JsonUtils.isBlankText(text) && !allPagesConfirmedBlank()) return "FAILED";
            return coverage.isComplete() && !"needs_review".equals(coverage.getOcrQualityStatus())
                    && coverage.getNeedsReviewPages().isEmpty() ? "PARSED" : "PARTIAL";
        }

        private boolean allPagesConfirmedBlank() {
            return coverage.isComplete() && coverage.getTotalPages() > 0
                    && coverage.getParsedPages() == coverage.getTotalPages()
                    && coverage.getBlankPages().size() == coverage.getTotalPages();
        }
    }

    public static final class PageText {

        private final int pageNo;
        private final String text;
        private final String status;

        public PageText(int pageNo, String text) {
            this(pageNo, text, "native");
        }

        public PageText(int pageNo, String text, String status) {
            this.pageNo = pageNo;
            this.text = text;
            this.status = status;
        }

        public int getPageNo() {
            return pageNo;
        }

        public String getText() {
            return text;
        }

        public String getStatus() { return status; }
    }
}
