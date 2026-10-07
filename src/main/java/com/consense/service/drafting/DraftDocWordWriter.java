package com.consense.service.drafting;

import com.consense.common.BizException;
import org.apache.poi.xwpf.usermodel.*;
import java.io.ByteArrayOutputStream;

/** Editable Word export of the same saved draft shown in PDF preview. */
public final class DraftDocWordWriter {
    private DraftDocWordWriter() { }
    public static byte[] write(String title, String content) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph heading = doc.createParagraph();
            XWPFRun run = heading.createRun(); run.setText(title); run.setBold(true); run.setFontSize(18);
            XWPFTable table = null;
            for (String line : content.split("\\r?\\n", -1)) {
                if (line.trim().matches("\\|?[\\s:|\\-]+\\|?")) continue;
                if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
                    String[] cells = line.trim().substring(1,line.trim().length()-1).split("\\|",-1);
                    XWPFTableRow row;
                    if (table == null) { table = doc.createTable(1,cells.length); row = table.getRow(0); }
                    else row = table.createRow();
                    while (row.getTableCells().size() < cells.length) row.addNewTableCell();
                    for (int i=0;i<cells.length;i++) row.getCell(i).setText(cells[i].trim());
                    continue;
                }
                table = null;
                XWPFParagraph p = doc.createParagraph();
                XWPFRun r = p.createRun(); r.setFontFamily("Arial"); r.setFontSize(11);
                if (line.startsWith("#")) { r.setBold(true); r.setFontSize(14); line = line.replaceFirst("^#{1,6}\\s*", ""); }
                r.setText(line);
            }
            doc.write(out); return out.toByteArray();
        } catch (Exception e) { throw new BizException(5001,"Word导出失败: " + e.getMessage()); }
    }
}
