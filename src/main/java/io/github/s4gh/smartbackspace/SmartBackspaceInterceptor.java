package io.github.s4gh.smartbackspace;

import javax.swing.text.BadLocationException;
import javax.swing.text.Caret;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;

import org.netbeans.api.editor.mimelookup.MimePath;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.modules.editor.indent.api.Indent;
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

        String leftOfCaret = safeGet(doc, lineStart, Math.max(0, dot - lineStart));
        String wholeLine = safeGet(doc, lineStart, Math.max(0, lineEnd - lineStart));

        boolean onlyWsLeftOfCaret = leftOfCaret.chars().allMatch(ch -> ch == ' ' || ch == '\t');
        boolean wholeLineIsWs = wholeLine.trim().isEmpty();
        
        if (!onlyWsLeftOfCaret || !wholeLineIsWs) {
            return;
        }

        // If it's the very first line, nothing to join with.
        if (lineIdx == 0) {
            return;
        }

        // Calculate logical line start using Indent API
        int logicalLineStart = calculateLogicalLineStart(doc, lineIdx);

        // If cursor is after logical line start, jump to logical line start
        if (dot > logicalLineStart) {
            int spacesToRemove = dot - logicalLineStart;
            doc.remove(logicalLineStart, spacesToRemove);
            caret.setDot(logicalLineStart);
            beforeDot = -1;
            return;
        }

        // Otherwise, remove the entire line and jump to previous line
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

    /**
     * Calculate the logical line start position using Indent API.
     * This determines where content SHOULD start based on the document's
     * indentation rules and context (e.g., inside blocks, after braces, etc.)
     * 
     * @param doc The document
     * @param lineIdx The line index
     * @return The offset where content should logically start
     */
    private int calculateLogicalLineStart(Document doc, int lineIdx) throws BadLocationException {
        Element root = doc.getDefaultRootElement();
        Element line = root.getElement(lineIdx);
        int lineStart = line.getStartOffset();
        int lineEnd = line.getEndOffset();
        
        String lineText = doc.getText(lineStart, lineEnd - lineStart);
        
        // If line has actual content (not just whitespace), return where it starts
        String trimmed = lineText.trim();
        boolean hasContent = !trimmed.isEmpty() && !trimmed.equals("\n") && !trimmed.equals("\r\n");
        
        if (hasContent) {
            // Line has content - find where non-whitespace starts
            int firstNonWs = 0;
            while (firstNonWs < lineText.length() && 
                   (lineText.charAt(firstNonWs) == ' ' || lineText.charAt(firstNonWs) == '\t')) {
                firstNonWs++;
            }
            return lineStart + firstNonWs;
        }
        
        // Line is blank - use Indent API to calculate proper indentation based on context
        Indent indent = Indent.get(doc);
        if (indent == null) {
            // Fallback: look at previous line
            return calculateLogicalLineStartFallback(doc, lineIdx);
        }
        
        indent.lock();
        try {
            // Save the original line content
            String originalContent = doc.getText(lineStart, lineEnd - lineStart);
            int originalLength = originalContent.length();
            
            // Reindent the line to calculate proper indentation based on context
            indent.reindent(lineStart, lineEnd);
            
            // Get the new line bounds (they may have shifted due to indentation changes)
            Element reindentedLine = root.getElement(lineIdx);
            int newLineStart = reindentedLine.getStartOffset();
            int newLineEnd = reindentedLine.getEndOffset();
            
            // Measure the indentation that was applied
            String reindentedContent = doc.getText(newLineStart, newLineEnd - newLineStart);
            int indentLength = 0;
            while (indentLength < reindentedContent.length() && 
                   (reindentedContent.charAt(indentLength) == ' ' || 
                    reindentedContent.charAt(indentLength) == '\t')) {
                indentLength++;
            }
            
            int logicalStart = newLineStart + indentLength;
            
            // Restore original content (undo the reindent)
            doc.remove(newLineStart, newLineEnd - newLineStart);
            doc.insertString(newLineStart, originalContent, null);
            
            // Return the calculated logical start position
            // (adjusting for the fact we're back to original line bounds)
            return lineStart + indentLength;
            
        } catch (BadLocationException e) {
            // If something goes wrong, fall back to manual calculation
            return calculateLogicalLineStartFallback(doc, lineIdx);
        } finally {
            indent.unlock();
        }
    }
    
    /**
     * Fallback method for calculating logical line start when Indent API
     * is unavailable or fails. Uses the previous non-blank line's indentation.
     */
    private int calculateLogicalLineStartFallback(Document doc, int lineIdx) throws BadLocationException {
        Element root = doc.getDefaultRootElement();
        Element line = root.getElement(lineIdx);
        int lineStart = line.getStartOffset();
        
        // Look at previous non-blank line's indentation
        int searchIdx = lineIdx - 1;
        while (searchIdx >= 0) {
            Element prevLine = root.getElement(searchIdx);
            int prevStart = prevLine.getStartOffset();
            int prevEnd = prevLine.getEndOffset();
            String prevText = doc.getText(prevStart, prevEnd - prevStart);
            
            int prevFirstNonWs = 0;
            while (prevFirstNonWs < prevText.length() && 
                   (prevText.charAt(prevFirstNonWs) == ' ' || prevText.charAt(prevFirstNonWs) == '\t')) {
                prevFirstNonWs++;
            }
            
            if (prevFirstNonWs < prevText.length() && 
                prevText.charAt(prevFirstNonWs) != '\n' && 
                prevText.charAt(prevFirstNonWs) != '\r') {
                return lineStart + prevFirstNonWs;
            }
            
            searchIdx--;
        }
        
        return lineStart;
    }

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