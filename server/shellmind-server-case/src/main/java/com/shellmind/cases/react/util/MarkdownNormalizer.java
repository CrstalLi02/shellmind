package com.shellmind.cases.react.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown format normalizer (v2 rewrite).
 * <p>
 * Model-produced Markdown often lacks newlines, so headings, lists, and tables glue onto one line.
 * This utility normalizes on the backend so content sent to the frontend conforms to GFM.
 * <p>
 * Core design:
 * 1. Protect code blocks: all later operations skip code content
 * 2. Processing order: tables before lists/headings (so || is not broken by list rules)
 * 3. Split table rows on || and auto-complete separator lines
 * 4. Idempotent: running normalize more than once yields the same result
 * 5. Placeholders use a SOH (\\u0001) prefix that trim must not swallow
 *
 * @author ShellMind
 */
public final class MarkdownNormalizer {

    private MarkdownNormalizer() {
    }

    private static final String CB_PREFIX = "\u0001CB";
    private static final String CB_SUFFIX = "\u0001";
    private static final String IC_PREFIX = "\u0001IC";
    private static final String TB_PREFIX = "\u0001TB";

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return text;

        // ═══ phase 0: protect code blocks ═══
        List<String> codeBlocks = new ArrayList<>();
        String r = protectCodeBlocks(text, codeBlocks);

        // ═══ phase 1: table handling (must run before line-break repair) ═══
        r = processTables(r);

        // ═══ phase 1.5: protect table rows so later list rules do not treat | -- | as a list marker ═══
        List<String> tableLines = new ArrayList<>();
        r = protectTableLines(r, tableLines);

        // ═══ phase 2: line-break repair ═══
        // 2a. Insert a trailing space after heading markers ##
        r = r.replaceAll("(#{2,6})([^\\s#])", "$1 $2");
        r = r.replaceAll("(^|\\n)(#)([^\\s#])", "$1$2 $3");

        // 2b. Line-break + blank line before a heading marker
        r = r.replaceAll("([^\\n\\s#])(#{1,6}\\s)", "$1\n\n$2");

        // 2d. Heading immediately followed by a table marker → line-break
        r = r.replaceAll("(#{1,6}\\s[^\\n|]+)(\\|)", "$1\n\n$2");

        // 2d2. Heading immediately followed by a code-block placeholder → line-break
        r = r.replaceAll("(#{1,6}\\s[^\\n\u0001]+)(\u0001CB)", "$1\n\n$2");

        // 2d3 removed — headings that include a period must not be force-broken; ReactMarkdown splits paragraphs naturally
        // 2e removed — do not force a new paragraph after a period; ReactMarkdown uses double newlines

        // 2f0. Insert a trailing space after an ordered-list marker (AI output may omit it: 1.config → 1. config)
        // Exclude version numbers: digit. immediately followed by a digit or another dot (e.g. 3.4.3)
        r = r.replaceAll("(\\d{1,2})\\.([^\\s\\d\\n\\.])", "$1. $2");

        // 2h0. Insert a trailing space after an unordered-list marker (AI output may omit it: -containerization → - containerization)
        // Only match when the marker is immediately followed by a CJK character; exclude UTF-8 (-8), x64-based, and similar English+numeric cases
        // Exclude hyphenated words: after a letter/digit, - is a hyphen, not a list marker (e.g. x64-architecture)
        r = r.replaceAll("(?<![a-zA-Z0-9\\u0001])([-*+])([\\u4e00-\\u9fa5])", "$1 $2");
        // 2h0-emoji. Insert a space when a list marker is immediately followed by an emoji
        r = r.replaceAll("([-*+])([\u2705\u274c\u26a0\u2764\u2b50\u2605\u2728\u2714\u2716])", "$1 $2");
        // 2h0-en. Also insert a space when a list marker is followed by an English capital letter (class/file names usually start with one)
        // e.g. -AdminService → - AdminService
        // Exclude hyphenated words: after a letter/digit, - is a hyphen, not a list marker (xfg-wrench, JSON-RPC, UTF-8)
        r = r.replaceAll("(?<![a-zA-Z0-9\\u0001])([-*+])([A-Z])", "$1 $2");
        // 2h0-en2. Insert a space when a list marker is followed by lowercase letters mixed with CJK
        // e.g. -dto directory → - dto directory; exclude pure-English hyphenation such as self-contained
        // Exclude hyphenated words: after a letter/digit, - is a hyphen, not a list marker (xfg-wrench framework)
        r = r.replaceAll("(?<![a-zA-Z0-9\\u0001])([-*+])([a-z]+)([\\u4e00-\\u9fa5])", "$1 $2$3");
        // 2h0-ext. Also insert a space when a list marker is immediately followed by a placeholder (bold/italic/inline code)
        // e.g. -**enhanced config** → after protect: -IC0 → insert space: - IC0 → restore: - **enhanced config**
        r = r.replaceAll("([-*+])(\u0001IC)", "$1 $2");

        // 2h1. Insert a space between a CJK character and a digit (e.g. update3item → update 3 item)
        // Exclude English cases (x64, UTF-8 do not need a space)
        r = r.replaceAll("([\\u4e00-\\u9fa5])(\\d)", "$1 $2");
        r = r.replaceAll("(?<!-)(\\d)([\\u4e00-\\u9fa5])", "$1 $2");

        // 2f. Line-break before an ordered list — exclude version numbers (e.g. 3.4.3 must not be split as a list)
        r = r.replaceAll("([^\\n\\d\\s.])(1\\.\\s)", "$1\n$2");

        // 2g. Line-break between ordered-list items — exclude version numbers and numeric markers inside headings
        // Exclude digit markers inside headings (in "## 1. config management", 1. is heading text, not a list item)
        // Do not break before a numeric marker that follows a hash and space
        r = r.replaceAll("([^\\n\\d.#\\s])(\\d{1,2}\\.\\s)", "$1\n$2");

        // 2h. Line-break before an unordered list — match any non-newline, non-space character followed by a list marker
        // A placeholder followed by a list marker also needs a line break (e.g. **core capabilities**- config management)
        // UTF-8 / x64-based safety: in those cases - has no space after it, so [-*+]\s does not fire
        r = r.replaceAll("([^\\n\\s])([-*+]\\s)", "$1\n$2");
        // 2i. Line-break before tree-drawing characters
        r = r.replaceAll("([^\\n])(├──|└──)", "$1\n$2");

        // 2j. Line-break before a code-block placeholder
        r = r.replaceAll("([^\\n\u0001])(\u0001CB)", "$1\n$2");

        // 2k. Line-break after a code-block placeholder
        r = r.replaceAll("(\u0001CB\\d+\u0001)([^\\n\u0001])", "$1\n$2");

        // ═══ phase 2.5: fix table rows newly exposed after phase-2 line-breaking ═══
        r = fixTableBlocks(r);

        // ═══ phase 3: blank-line repair ═══
        r = ensureBlankLines(r);

        // ═══ phase 4: cleanup ═══
        r = r.replaceAll("\\n{3,}", "\n\n");
        r = r.replaceAll("[ \\t]+\\n", "\n");
        r = r.replaceAll("^\\s+", "").replaceAll("\\s+$", "");

        // ═══ phase 5: restore table rows (must run before restoring code blocks; table rows may contain IC/CB placeholders) ═══
        r = restoreTableLines(r, tableLines);

        // ═══ phase 5.5: restore code blocks and inline elements ═══
        r = restoreCodeBlocks(r, codeBlocks);

        // ═══ phase 6: second-pass normalize of Markdown showcase-block content ═══
        // Models often wrap Markdown in ```markdown ... ``` as a "display" fence.
        // That content is not real code and still needs normalize.
        // Other code blocks (java/python, etc.) are left unchanged.
        r = normalizeMarkdownShowcaseBlocks(r);

        // ═══ phase 7: compact blank lines inside table blocks (must be last; earlier rules may have inserted blanks) ═══
        r = compactTableBlocks(r);

        return r;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Code-block protect / restore
    // ═══════════════════════════════════════════════════════════════

    private static String protectCodeBlocks(String text, List<String> codeBlocks) {
        String result = text;
        // 1. Fenced code block ```...``` → CB (strict match; must be closed)
        result = replaceAndStore(result, Pattern.compile("```[^\\n]*\\n[\\s\\S]*?```"), codeBlocks, CB_PREFIX);
        // 1b. Fallback: unclosed fenced code block (AI streaming output may omit the closing ```)
        result = replaceAndStore(result, Pattern.compile("```[^\\n]*\\n[\\s\\S]*$"), codeBlocks, CB_PREFIX);
        result = replaceAndStore(result, Pattern.compile("`[^`\n]+`"), codeBlocks, IC_PREFIX);
        // Protect bold markers **...** so * is not mis-matched by list rules as an unordered-list marker
        // Must extract before italics *...*, otherwise ** would be split into two *
        result = replaceAndStore(result, Pattern.compile("\\*\\*[^*\\n]+\\*\\*"), codeBlocks, IC_PREFIX);
        // Protect italic markers *...* — bold is already extracted, remaining single * is italic
        result = replaceAndStore(result, Pattern.compile("\\*[^*\\n]+\\*"), codeBlocks, IC_PREFIX);
        return result;
    }

    private static String replaceAndStore(String text, Pattern pattern, List<String> store, String prefix) {
        Matcher m = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String placeholder = prefix + store.size() + CB_SUFFIX;
            store.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String restoreCodeBlocks(String text, List<String> codeBlocks) {
        String result = text;
        for (int i = codeBlocks.size() - 1; i >= 0; i--) {
            // Strip junk text at the start of code-block content.
            // When exported from Feishu/Yuque and similar rich-text editors, a "Copy code" button label is written into the first line.
            // Pattern: ```text\nCopy\n... → remove the "Copy" line
            String cleaned = stripCopyLabelFromCodeBlock(codeBlocks.get(i));
            // Try both prefixes: CB (code block) and IC (inline elements)
            result = result.replace(CB_PREFIX + i + CB_SUFFIX, cleaned);
            result = result.replace(IC_PREFIX + i + CB_SUFFIX, codeBlocks.get(i));
        }
        return result;
    }

    /**
     * Remove a junk "Copy" line at the start of a code-block body.
     * When Feishu/Yuque and similar rich-text editors export Markdown, a "Copy code" button label is written into the fence:
     * ```text
     * Copy
     * actual code...
     * ```
     * Detects and removes that junk line so the code-block content stays clean.
     *
     * @param codeBlock full code-block text (including opening and closing ```)
     * @return code-block text after cleanup
     */
    private static String stripCopyLabelFromCodeBlock(String codeBlock) {
        // Only handle fenced code blocks (starting with ```)
        if (!codeBlock.startsWith("```")) return codeBlock;
        // Find the first newline after ``` and extract the body
        int firstNewline = codeBlock.indexOf('\n');
        if (firstNewline < 0) return codeBlock;
        String content = codeBlock.substring(firstNewline + 1);
        // Check whether the first content line is "Copy" (optional surrounding whitespace)
        int secondNewline = content.indexOf('\n');
        String firstLine;
        String rest;
        if (secondNewline < 0) {
            firstLine = content.trim();
            rest = "";
        } else {
            firstLine = content.substring(0, secondNewline).trim();
            rest = content.substring(secondNewline + 1);
        }
        if ("Copy".equals(firstLine) || "Copy code".equals(firstLine) || "复制".equals(firstLine) || "复制代码".equals(firstLine)) {
            return codeBlock.substring(0, firstNewline + 1) + rest;
        }
        return codeBlock;
    }

    /**
     * Protect table rows (lines starting with |) so list rules do not match them by mistake.
     * Content such as | -- | would otherwise be treated as a list by [-*+]\\s, splitting the separator into extra lines.
     * Aligned with the frontend protectTableLines logic.
     */
    private static String protectTableLines(String text, List<String> tableLines) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("|") && trimmed.length() > 1 && isTableRow(lines[i])) {
                String placeholder = TB_PREFIX + tableLines.size() + CB_SUFFIX;
                tableLines.add(lines[i]);
                sb.append(placeholder);
            } else {
                sb.append(lines[i]);
            }
        }
        return sb.toString();
    }

    private static String restoreTableLines(String text, List<String> tableLines) {
        String result = text;
        for (int i = tableLines.size() - 1; i >= 0; i--) {
            result = result.replace(TB_PREFIX + i + CB_SUFFIX, tableLines.get(i));
        }
        return result;
    }

    /**
     * Run a second normalize pass on Markdown showcase blocks (fences starting with ```markdown / ```md).
     * Models often wrap Markdown in ```markdown ... ``` as a display fence; that content is not real code
     * and needs the same line-breaking / space-insertion as outer text.
     * Other code blocks (java/python/shell, etc.) are left unchanged.
     */
    private static String normalizeMarkdownShowcaseBlocks(String text) {
        // Match a ```markdown or ```md fence and normalize its inner content
        // AI output may be ```markdown\n or ```markdown immediately followed by content (no newline)
        Pattern p = Pattern.compile("(```(?:markdown|md)\\n?)([\\s\\S]*?)(```)");
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String inner = m.group(2);
            // Normalize inner content once (recursive, but a markdown showcase block will not contain another markdown fence, so it cannot recurse infinitely)
            String normalizedInner = normalize(inner);
            // markdown/md fences are the model's "display intent", not code that must be shown verbatim; unwrap them to body text before rendering.
            m.appendReplacement(sb, Matcher.quoteReplacement(normalizedInner));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  table handling
    // ═══════════════════════════════════════════════════════════════


    /**
     * Remove consecutive duplicate separator rows.
     * mergeTableFragments and fixTableBlocks may each emit a separator,
     * which would otherwise produce multiple adjacent | --- | --- | lines.
     */
    private static String dedupSeparatorRows(String text) {
        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();
        boolean lastWasSep = false;
        for (int idx = 0; idx < lines.length; idx++) {
            String line = lines[idx];
            String trimmed = line.trim();
            boolean isSep = trimmed.startsWith("|") && trimmed.contains("---")
                    && trimmed.matches("\\|\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?");
            if (isSep && lastWasSep) {
                continue;
            }
            if (isSep) {
                lastWasSep = true;
                // Drop a blank line that follows a separator row
                if (!result.isEmpty() && result.get(result.size() - 1).trim().isEmpty()) {
                    result.remove(result.size() - 1);
                }
            } else if (!trimmed.isEmpty()) {
                lastWasSep = false;
            }
            result.add(line);
        }
        return String.join("\n", result);
    }
    private static String processTables(String text) {
        String r = mergeTableFragments(text);
        r = cleanupOrphanedDashFragments(r);
        r = splitTableContentFromListItems(r);
        r = splitDoublePipeTables(r);
        r = fixTableBlocks(r);
        r = dedupSeparatorRows(r);
        r = compactTableBlocks(r);
        return r;
    }

    /**
     * Compact blank lines inside a table block.
     * Consecutive table rows (starting with |) must not have a blank line between them,
     * or Markdown renderers will not recognize the table.
     * Only blank lines between consecutive table rows are removed; blanks before/after the block are kept.
     */
    private static String compactTableBlocks(String text) {
        String[] lines = text.split("\\n", -1);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            // If the current line is blank, check whether both neighbors are table rows
            if (trimmed.isEmpty() && !result.isEmpty()) {
                String prev = result.get(result.size() - 1).trim();
                // Look at the next line (if any)
                String next = (i + 1 < lines.length) ? lines[i + 1].trim() : "";
                if (prev.startsWith("|") && prev.endsWith("|") && next.startsWith("|") && next.endsWith("|")) {
                    continue; // Skip blank lines between table rows
                }
            }
            result.add(lines[i]);
        }
        return String.join("\n", result);
    }

    /**
     * Clean orphan dash lines left by fragmented table separator rows.
     * e.g. "--", "-", "------" that are not table rows (do not start with |) — pure short-dash lines.
     */
    private static String cleanupOrphanedDashFragments(String text) {
        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            // Delete orphan pure short-dash fragments (e.g. "--", "-", "------")
            if (trimmed.matches("-{1,50}") && !trimmed.equals("---")) {
                continue;
            }
            // Delete orphan short-dash + pipe fragments (e.g. "------|", "---------|")
            if (trimmed.matches("-{2,50}\\|")) {
                continue;
            }
            // Handle a glued fragment line, e.g. "---------| | IC24 | External API contract |"
            if (trimmed.matches("-{2,50}\\|.*\\|.*")) {
                int pipeIdx = trimmed.indexOf('|');
                String afterPipe = trimmed.substring(pipeIdx + 1).trim();
                result.add("| " + afterPipe);
                continue;
            }
            // Delete pure short-dash fragments inside a table row: | ------ | or | --- |--
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                // Check whether the line is only |, -, and spaces
                String noSep = trimmed.replaceAll("[|\\-\\s:]", "");
                if (noSep.isEmpty() && !trimmed.contains("---")) {
                    // Pure fragment line (no ---): skip
                    continue;
                }
            }
            result.add(line);
        }
        return String.join("\n", result);
    }

    /**
     * Merge table rows that AI streaming output fragmented.
     * While streaming, the model may split a separator |---|---| into several fragments:
     *   |          (pipe only)
     *   ------|    (separator-content fragment)
     *   ------|    (another fragment)
     * This method merges those fragments into a complete table separator row.
     */
    private static String mergeTableFragments(String text) {
        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();

            // Detect fragment pattern: a lone | line immediately followed by fragment lines
            if (line.equals("|")) {
                // Collect following fragment lines (------| and blank lines)
                int j = i + 1;
                // skip blank lines
                while (j < lines.length && lines[j].trim().isEmpty()) j++;
                // collect fragments
                List<String> fragments = new ArrayList<>();
                while (j < lines.length && lines[j].trim().matches("-+\\|?")) {
                    fragments.add(lines[j].trim());
                    j++;
                    // Skip blank lines between fragments
                    while (j < lines.length && lines[j].trim().isEmpty()) j++;
                }

                if (!fragments.isEmpty()) {
                    // Merge fragments into a complete separator row
                    // Each ------| represents one cell's --- separator
                    // Rebuild as a standard separator: | --- | --- | ... |
                    StringBuilder separator = new StringBuilder("|");
                    for (String frag : fragments) {
                        separator.append(" --- |");
                    }
                    result.add(separator.toString());
                    i = j;
                } else {
                    // No fragments: | should have been followed by separator content; drop the orphan |
                    // If the next non-empty line is a table data row, | is a fragmented separator start
                    int nextNonEmpty = i + 1;
                    while (nextNonEmpty < lines.length && lines[nextNonEmpty].trim().isEmpty()) nextNonEmpty++;
                    if (nextNonEmpty < lines.length && lines[nextNonEmpty].trim().startsWith("|") && lines[nextNonEmpty].trim().length() > 1) {
                        // | is a fragmented separator start, but later content was already handled by other rules
                        // Need to check whether the previous line is a table data row
                        // If the previous line is a table row, insert a separator
                        boolean prevIsTable = !result.isEmpty() && result.get(result.size() - 1).trim().startsWith("|") && result.get(result.size() - 1).trim().length() > 1;
                        if (prevIsTable) {
                            // count columns
                            String prevLine = result.get(result.size() - 1).trim();
                            int colCount = countColumns(prevLine);
                            result.add(buildSeparatorRow(colCount));
                        }
                        // Skip | and blank lines
                        i = nextNonEmpty;
                    } else {
                        // Orphan | cannot be handled; keep it
                        result.add(lines[i]);
                        i++;
                    }
                }
            } else {
                // Also detect standalone fragment lines ------| (may appear after a | line was already removed)
                if (line.matches("-+\\|?")) {
                    // Standalone fragment line; may be leftover separator content
                    // check whether the previous line is a table data row
                    boolean prevIsTable = !result.isEmpty() && result.get(result.size() - 1).trim().startsWith("|") && result.get(result.size() - 1).trim().length() > 1;
                    if (prevIsTable) {
                        // Separator-row fragment: skip (fixSingleTable will complete the separator)
                        i++;
                        continue;
                    }
                }
                result.add(lines[i]);
                i++;
            }
        }
        return String.join("\n", result);
    }

    /**
     * Split list/heading content that was glued onto a table row.
     * The model sometimes glues the last table row to the following list:
     *   |  downside | hard to extend |\u0001IC1\u0001- ✅ private constructor
     *
     * Strategy: scan right to left for the first | immediately followed by non-table content, then split there.
     * Non-table content = IC/CB placeholders, **, - list markers, ## headings.
     * Note: do not use String.trim() (it would swallow SOH \\u0001); use trimAsciiOnly only.
     */
    private static String splitTableContentFromListItems(String text) {
        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|") && trimmed.length() > 1) {
                int splitPos = findSplitPosition(trimmed);
                if (splitPos > 0 && splitPos < trimmed.length() - 1) {
                    String tablePart = trimmed.substring(0, splitPos + 1); // includes |
                    String trailing = trimmed.substring(splitPos + 1);
                    if (countPipes(tablePart) >= 2) {
                        result.add(tablePart);
                        result.add("");
                        result.add(trimAsciiOnly(trailing));
                        continue;
                    }
                }
                result.add(line);
            } else {
                result.add(line);
            }
        }
        return String.join("\n", result);
    }

    /**
     * Find the last valid split point in the table row, scanning right to left.
     * Returns the index of that |, or -1 if there is no glued content.
     */
    private static int findSplitPosition(String line) {
        for (int pos = line.length() - 1; pos >= 0; pos--) {
            if (line.charAt(pos) != '|') continue;
            String after = line.substring(pos + 1);
            if (after.isEmpty()) continue;
            if (after.startsWith(IC_PREFIX) || after.startsWith(CB_PREFIX)
                    || after.startsWith("**") || after.startsWith("##")
                    || after.matches("-\\s*[✅❌⚠].*") || after.matches("-\\s+\\S.*")) {
                String before = line.substring(0, pos);
                if (countPipes(before) >= 1) {
                    return pos;
                }
            }
        }
        return -1;
    }

    /**
     * Strip only ASCII space (0x20), tab (0x09), newline (0x0a), and CR (0x0d).
     * Does not remove SOH \\u0001 or other control characters, so placeholder prefixes/suffixes stay intact.
     */
    private static String trimAsciiOnly(String s) {
        if (s == null) return null;
        int start = 0, end = s.length();
        while (start < end && isTrimChar(s.charAt(start))) start++;
        while (end > start && isTrimChar(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isTrimChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static int countPipes(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == '|') count++;
        }
        return count;
    }

    private static String splitDoublePipeTables(String text) {
        if (!text.contains("||")) return text;

        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\\n", -1);

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) result.append("\n");
            if (lines[i].contains("||")) {
                result.append(splitSingleGluedLine(lines[i]));
            } else {
                result.append(lines[i]);
            }
        }
        return result.toString();
    }

    private static String splitSingleGluedLine(String line) {
        int firstPipe = line.indexOf('|');
        if (firstPipe < 0) return line;

        String prefix = "";
        String tablePart = line;
        if (firstPipe > 0) {
            String beforeFirstPipe = line.substring(0, firstPipe).trim();
            if (!beforeFirstPipe.isEmpty() && !beforeFirstPipe.startsWith("|")) {
                prefix = beforeFirstPipe + "\n";
                tablePart = line.substring(firstPipe);
            }
        }

        String[] segments = tablePart.split("\\|\\|");
        List<String> rows = new ArrayList<>();

        for (String seg : segments) {
            String row = seg.trim();
            if (row.isEmpty()) continue;

            // check whether this segment has trailing non-table content
            int trailingIdx = findTrailingContentIndex(row);
            String tableRowPart;
            String trailingContent = null;

            if (trailingIdx > 0) {
                // trailingIdx points at the |; that | belongs to the table-row tail
                tableRowPart = row.substring(0, trailingIdx + 1).trim(); // includes |
                trailingContent = row.substring(trailingIdx + 1).trim();  // content after |
            } else {
                tableRowPart = row;
            }

            if (!tableRowPart.isEmpty()) {
                if (!tableRowPart.startsWith("|")) tableRowPart = "|" + tableRowPart;
                if (!tableRowPart.endsWith("|")) tableRowPart = tableRowPart + "|";
                if (isSeparatorContent(tableRowPart)) {
                    tableRowPart = formatSeparatorRow(tableRowPart);
                } else {
                    tableRowPart = normalizeCellSpacing(tableRowPart);
                }
                rows.add(tableRowPart);
            }

            if (trailingContent != null && !trailingContent.isEmpty()) {
                rows.add(trailingContent);
            }
        }
        return prefix + String.join("\n", rows);
    }

    /**
     * Find the | in a table-row segment that is immediately followed by non-table content.
     * Returns -1 if there is no trailing content.
     */
    private static int findTrailingContentIndex(String segment) {
        // | immediately followed by a ## heading marker (take the first match; once a heading starts, everything after it is non-table)
        Matcher m = Pattern.compile("\\|\\s*(#{1,6}\\s)").matcher(segment);
        if (m.find()) {
            return m.start();
        }

        // | immediately followed by CJK, with no further |, and the text is long (>30) or contains punctuation
        Matcher m2 = Pattern.compile("\\|\\s*([\\u4e00-\\u9fa5])").matcher(segment);
        int lastMatch = -1;
        while (m2.find()) {
            String after = segment.substring(m2.end());
            if (!after.contains("|")) {
                String textAfterPipe = segment.substring(m2.start() + 1).trim();
                if (textAfterPipe.matches(".*[。！？].*") || textAfterPipe.length() > 30) {
                    lastMatch = m2.start();
                }
            }
        }
        return lastMatch;
    }

    private static boolean isSeparatorContent(String row) {
        String trimmed = row.trim();
        if (!trimmed.startsWith("|") || trimmed.length() <= 2) return false;
        String inner = trimmed.substring(1, trimmed.endsWith("|") ? trimmed.length() - 1 : trimmed.length());
        if (inner.isEmpty()) return false;
        String[] cells = inner.split("\\|");
        boolean hasDash = false;
        for (String cell : cells) {
            String c = cell.trim();
            if (c.isEmpty()) continue;
            if (!c.matches("[-:]+")) return false;
            if (c.contains("-")) hasDash = true;
        }
        return hasDash;
    }

    private static String formatSeparatorRow(String row) {
        String trimmed = row.trim();
        String inner = trimmed.substring(1, trimmed.endsWith("|") ? trimmed.length() - 1 : trimmed.length());
        String[] cells = inner.split("\\|");
        StringBuilder sb = new StringBuilder("|");
        for (String cell : cells) {
            String c = cell.trim();
            if (c.isEmpty()) continue;
            sb.append(" ").append(c).append(" |");
        }
        return sb.toString();
    }

    private static String normalizeCellSpacing(String row) {
        String trimmed = row.trim();
        String inner = trimmed.substring(1, trimmed.endsWith("|") ? trimmed.length() - 1 : trimmed.length());
        String[] cells = inner.split("\\|", -1);
        StringBuilder sb = new StringBuilder("|");
        for (String cell : cells) {
            sb.append(" ").append(cell.trim()).append(" |");
        }
        return sb.toString();
    }

    private static String fixTableBlocks(String text) {
        String[] lines = text.split("\\n", -1);
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            if (isTableRow(lines[i])) {
                List<String> tableRows = new ArrayList<>();
                while (i < lines.length && isTableRow(lines[i])) {
                    tableRows.add(lines[i]);
                    i++;
                }
                result.addAll(fixSingleTable(tableRows));
            } else {
                result.add(lines[i]);
                i++;
            }
        }
        return String.join("\n", result);
    }

    private static boolean isTableRow(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.length() > 1;
    }

    private static List<String> fixSingleTable(List<String> rows) {
        if (rows.isEmpty()) return rows;
        if (isSeparatorContent(rows.get(0))) return rows;

        int colCount = countColumns(rows.get(0));
        List<String> fixed = new ArrayList<>();

        for (int j = 0; j < rows.size(); j++) {
            String row = rows.get(j);
            if (isSeparatorContent(row)) {
                int sepCols = countColumns(row);
                if (sepCols != colCount) {
                    fixed.add(buildSeparatorRow(colCount));
                } else {
                    fixed.add(formatSeparatorRow(row));
                }
            } else {
                // Ensure non-separator rows also have correct | wrapping and a trailing |
                if (!row.startsWith("|")) row = "|" + row;
                if (!row.endsWith("|")) row = normalizeCellSpacing(row);
                fixed.add(row);
                if (j == 0) {
                    boolean nextIsSep = (j + 1 < rows.size()) && isSeparatorContent(rows.get(j + 1));
                    if (!nextIsSep) {
                        fixed.add(buildSeparatorRow(colCount));
                    }
                }
            }
        }
        return fixed;
    }

    private static int countColumns(String row) {
        String trimmed = row.trim();
        if (!trimmed.startsWith("|")) return 0;
        String inner = trimmed;
        if (inner.startsWith("|")) inner = inner.substring(1);
        if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);
        return inner.split("\\|", -1).length;
    }

    private static String buildSeparatorRow(int colCount) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < colCount; c++) sb.append(" --- |");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Blank-line repair (process line by line)
    // ═══════════════════════════════════════════════════════════════

    private static String ensureBlankLines(String text) {
        String[] lines = text.split("\\n", -1);
        List<String> result = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                // Note: do not use String.trim(); it would swallow SOH \\u0001 control characters
                String prev = stripTrailingWhitespace(lines[i - 1]);
                String curr = stripTrailingWhitespace(lines[i]);

                if (prev.isEmpty()) {
                    result.add(lines[i]);
                    continue;
                }

                boolean needBlank = false;

                if (curr.matches("#{1,6}\\s.*") && !prev.matches("#{1,6}\\s.*")) needBlank = true;
                if (prev.matches("#{1,6}\\s.*") && !curr.matches("#{1,6}\\s.*") && !curr.isEmpty()
                        && !curr.startsWith("|") && !isSeparatorContent(curr)) needBlank = true;
                if (curr.matches("\\d+\\.\\s.*") && !prev.matches("\\d+\\.\\s.*")) needBlank = true;
                if (curr.matches("[-*+]\\s.*") && !prev.matches("[-*+]\\s.*")) needBlank = true;
                if (curr.startsWith("|") && !prev.startsWith("|")) needBlank = true;
                if (prev.startsWith("|") && !curr.startsWith("|") && !curr.isEmpty()) needBlank = true;
                if (curr.startsWith(CB_PREFIX) && !prev.startsWith(CB_PREFIX)) needBlank = true;
                if (prev.endsWith(CB_SUFFIX) && !curr.startsWith(CB_PREFIX) && !curr.isEmpty()) needBlank = true;

                if (needBlank) result.add("");
            }
            result.add(lines[i]);
        }
        return String.join("\n", result);
    }

    /** Safer trim substitute: strip only ASCII whitespace (space, tab), never swallow control characters. */
    private static String stripTrailingWhitespace(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) end--;
        return s.substring(0, end);
    }
}
