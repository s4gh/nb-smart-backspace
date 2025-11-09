package io.github.s4gh.smartbackspace;

import javax.swing.text.BadLocationException;
import javax.swing.text.Caret;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;

import org.netbeans.api.editor.mimelookup.MimePath;
import org.netbeans.api.editor.mimelookup.MimeRegistration;

import org.netbeans.spi.editor.typinghooks.DeletedTextInterceptor;

public final class SmartBackspaceInterceptor implements DeletedTextInterceptor {

    private int beforeDot = -1;
    private boolean hadTextToLeftBefore = false;

    @Override
    public boolean beforeRemove(DeletedTextInterceptor.Context ctx) throws BadLocationException {
        JTextComponent comp = ctx.getComponent();
        if (comp == null) {
            return false;
        }
        
        // Remember caret before default handling; do not modify the document here.
        beforeDot = comp.getCaret().getDot();
        
        Document doc = ctx.getDocument();
        Element root = doc.getDefaultRootElement();
        int lineIdx = root.getElementIndex(beforeDot);
        Element line = root.getElement(lineIdx);
        int lineStart = line.getStartOffset();
        String lineTextToLeft = safeGet(doc, lineStart, Math.max(0, beforeDot - lineStart));
        hadTextToLeftBefore = lineTextToLeft != null && !"".equals(lineTextToLeft.trim());
        
        return false; // allow default deletion to proceed
    }

    @Override
    public void remove(DeletedTextInterceptor.Context ctx) throws BadLocationException {

        if (hadTextToLeftBefore) {
            beforeDot = -1;
            hadTextToLeftBefore = false;
            return; // keep the single-character deletion only
        }
        
        JTextComponent comp = ctx.getComponent();
        if (comp == null) {
            
            return;
        }

        Caret caret = comp.getCaret();
        Document doc = ctx.getDocument();

        // Direction: Backspace moves caret left; Delete keeps it in place.
        int afterDot = caret.getDot();
        boolean isBackspace = (beforeDot >= 0) && (afterDot < beforeDot);

        // Only act on Backspace.
        if (!isBackspace) {
            return;
        }

        // Guard: ignore if selection exists (we only target single-char delete).
        if (caret.getDot() != caret.getMark()) {
            return;
        }

        // Work with the current line (after the default removal took place).
        int dot = afterDot;
        Element root = doc.getDefaultRootElement();
        int lineIdx = root.getElementIndex(dot);
        Element line = root.getElement(lineIdx);
        int lineStart = line.getStartOffset();
        int lineEnd = line.getEndOffset();
        int logicalLineStart = calculateLogicalLineStart(doc, lineIdx);

        String leftOfCaret = safeGet(doc, lineStart, Math.max(0, dot - lineStart));
        String wholeLine = safeGet(doc, lineStart, Math.max(0, lineEnd - lineStart));

        boolean onlyWsLeftOfCaret = leftOfCaret.chars().allMatch(ch -> ch == ' ' || ch == '\t');

        boolean wholeLineIsWs = wholeLine.trim().isEmpty();
        if (!onlyWsLeftOfCaret || !wholeLineIsWs) { // added || !wholeLineIsWs
            return;
        }

        // If it's the very first line, nothing to join with.
        if (lineIdx == 0) {
            return;
        }

        if (dot > logicalLineStart) {
            int spacesToRemove = lineEnd - dot;
            doc.remove(lineStart, spacesToRemove);
            caret.setDot(logicalLineStart);
            return;
        }

        // Compute previous line content end, then delete this whole (whitespace) line (incl. its EOL)
        Element prev = root.getElement(lineIdx - 1);
        int prevEnd = Math.max(prev.getEndOffset() - 1, 0); // before its '\n'

        // Remove the current line including its line break.
        int removeLen = lineEnd - lineStart;
        doc.remove(lineStart, removeLen);

        // Put caret at the end of previous line (content end).
        caret.setDot(prevEnd);

        // Reset per-event state
        beforeDot = -1;
    }

    @Override
    public void afterRemove(DeletedTextInterceptor.Context ctx) {
        beforeDot = -1;
        hadTextToLeftBefore = false;
    }

    @Override
    public void cancelled(DeletedTextInterceptor.Context ctx) {
        beforeDot = -1;
        hadTextToLeftBefore = false;
    }

    private static String safeGet(Document d, int off, int len) {
        try {
            return len > 0 ? d.getText(off, len) : "";
        } catch (BadLocationException e) {
            return "";
        }
    }

    private int calculateLogicalLineStart(Document doc, int lineIdx) throws BadLocationException {
        Element root = doc.getDefaultRootElement();
        Element line = root.getElement(lineIdx);
        int lineStart = line.getStartOffset();
        int lineEnd = line.getEndOffset();

        // Get the actual line content
        String lineText = doc.getText(lineStart, lineEnd - lineStart);

        // Find where non-whitespace content starts on current line
        int firstNonWs = 0;
        while (firstNonWs < lineText.length()
                && (lineText.charAt(firstNonWs) == ' ' || lineText.charAt(firstNonWs) == '\t')) {
            firstNonWs++;
        }

        // If current line has content, use its indentation
        if (firstNonWs < lineText.length() && lineText.charAt(firstNonWs) != '\n' && lineText.charAt(firstNonWs) != '\r') {
            return lineStart + firstNonWs;
        }

        // Current line is blank - look at previous non-blank lines to infer indentation
        int searchIdx = lineIdx - 1;
        while (searchIdx >= 0) {
            Element prevLine = root.getElement(searchIdx);
            int prevStart = prevLine.getStartOffset();
            int prevEnd = prevLine.getEndOffset();
            String prevText = doc.getText(prevStart, prevEnd - prevStart);

            // Find first non-whitespace in previous line
            int prevFirstNonWs = 0;
            while (prevFirstNonWs < prevText.length()
                    && (prevText.charAt(prevFirstNonWs) == ' ' || prevText.charAt(prevFirstNonWs) == '\t')) {
                prevFirstNonWs++;
            }

            // If we found a line with actual content, use its indentation level
            if (prevFirstNonWs < prevText.length()
                    && prevText.charAt(prevFirstNonWs) != '\n'
                    && prevText.charAt(prevFirstNonWs) != '\r') {

                // Extract the indentation string from the previous line
                String prevIndent = prevText.substring(0, prevFirstNonWs);

                // Apply that indentation level to current line position
                return lineStart + prevIndent.length();
            }

            searchIdx--;
        }

        // No previous content found - assume no indentation
        return lineStart;
    }

//    private int calculateLogicalLineStartWithIndentAPI(Document doc, int lineIdx) {
//        try {
//            Element root = doc.getDefaultRootElement();
//            Element line = root.getElement(lineIdx);
//            int lineStart = line.getStartOffset();
//
//            // Use NetBeans Indent API to get proper indentation
//            org.netbeans.modules.editor.indent.api.Indent indent
//                    = org.netbeans.modules.editor.indent.api.Indent.get(doc);
//
//            if (indent != null) {
//                indent.lock();
//                try {
//                    // Get the indentation for this line
//                    int indentLevel = indent.indentNewLine(lineStart); //indentNewLine
//                    return indentLevel;
//                } finally {
//                    indent.unlock();
//                }
//            }
//        } catch (BadLocationException e) {
//            // Fall back to manual calculation
//        }
//
//        // Fallback to the manual method
//        try {
//            return calculateLogicalLineStart(doc, lineIdx);
//        } catch (BadLocationException e) {
//            Element root = doc.getDefaultRootElement();
//            Element line = root.getElement(lineIdx);
//            return line.getStartOffset();
//        }
//    }

    // --- Registrations (add as many MIME types as you need) ---
    @MimeRegistration(service = DeletedTextInterceptor.Factory.class, mimeType = "text/x-java")
    public static final class JavaFactory implements DeletedTextInterceptor.Factory {

        @Override
        public DeletedTextInterceptor createDeletedTextInterceptor(MimePath mimePath) {
            return new SmartBackspaceInterceptor();
        }
    }

    @MimeRegistration(service = DeletedTextInterceptor.Factory.class, mimeType = "text/plain")
    public static final class PlainFactory implements DeletedTextInterceptor.Factory {

        @Override
        public DeletedTextInterceptor createDeletedTextInterceptor(MimePath mimePath) {
            return new SmartBackspaceInterceptor();
        }
    }
}
