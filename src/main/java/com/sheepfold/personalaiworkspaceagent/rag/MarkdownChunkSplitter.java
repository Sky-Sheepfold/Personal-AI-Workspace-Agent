package com.sheepfold.personalaiworkspaceagent.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Markdown-aware splitter that preserves heading and paragraph structure.
 */
public class MarkdownChunkSplitter {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^\\s{0,3}#{1,6}\\s+.*$");
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("^\\s*```.*$");
    private static final String HEADING_PATH_PREFIX = "Heading Path: ";

    private final int maxChunkSize;
    private final int minChunkSizeChars;
    private final int minChunkLengthToEmbed;
    private final int maxNumChunks;
    private final boolean keepSeparator;

    public MarkdownChunkSplitter(
            int maxChunkSize,
            int minChunkSizeChars,
            int minChunkLengthToEmbed,
            int maxNumChunks,
            boolean keepSeparator) {
        this.maxChunkSize = Math.max(200, maxChunkSize);
        this.minChunkSizeChars = Math.max(1, Math.min(minChunkSizeChars, this.maxChunkSize));
        this.minChunkLengthToEmbed = Math.max(1, Math.min(minChunkLengthToEmbed, this.maxChunkSize));
        this.maxNumChunks = Math.max(1, maxNumChunks);
        this.keepSeparator = keepSeparator;
    }

    public List<String> split(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return List.of();
        }

        String normalizedMarkdown = normalizeMarkdown(markdown);
        if (!StringUtils.hasText(normalizedMarkdown)) {
            return List.of();
        }

        List<String> roughChunks = new ArrayList<>();
        for (MarkdownSection section : splitBySections(normalizedMarkdown)) {
            appendSectionChunks(section, roughChunks);
            if (roughChunks.size() >= maxNumChunks * 2) {
                break;
            }
        }

        List<String> mergedChunks = mergeSmallChunks(roughChunks);
        List<String> result = new ArrayList<>();
        for (String chunk : mergedChunks) {
            String normalizedChunk = chunk == null ? "" : chunk.trim();
            if (!StringUtils.hasText(normalizedChunk)) {
                continue;
            }
            if (normalizedChunk.length() < minChunkLengthToEmbed) {
                continue;
            }
            result.add(normalizedChunk);
            if (result.size() >= maxNumChunks) {
                break;
            }
        }

        return result;
    }

    private String normalizeMarkdown(String markdown) {
        return markdown
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }

    private List<MarkdownSection> splitBySections(String markdown) {
        List<MarkdownSection> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inCodeFence = false;
        String[] headingStack = new String[6];
        List<String> activeHeadingPath = List.of();

        String[] lines = markdown.split("\n", -1);
        for (String line : lines) {
            if (!inCodeFence && isHeadingLine(line)) {
                if (current.length() > 0) {
                    addSection(sections, current.toString(), activeHeadingPath);
                    current.setLength(0);
                }

                updateHeadingStack(headingStack, line);
                activeHeadingPath = snapshotHeadingPath(headingStack);
            }

            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);

            if (isCodeFenceLine(line)) {
                inCodeFence = !inCodeFence;
            }
        }

        if (current.length() > 0) {
            addSection(sections, current.toString(), activeHeadingPath);
        }

        return sections;
    }

    private void appendSectionChunks(MarkdownSection section, List<String> target) {
        if (section == null || !StringUtils.hasText(section.content())) {
            return;
        }

        String sectionText = section.content();
        String headingPrefix = buildHeadingPathPrefix(section.headingPath());

        if (sectionText.length() <= maxChunkSize) {
            target.add(addHeadingPrefix(sectionText, headingPrefix));
            return;
        }

        String separator = keepSeparator ? "\n\n" : "\n";
        StringBuilder current = new StringBuilder();

        for (String paragraph : splitParagraphs(sectionText)) {
            if (!StringUtils.hasText(paragraph)) {
                continue;
            }

            String normalizedParagraph = paragraph.trim();
            if (normalizedParagraph.length() > maxChunkSize) {
                if (current.length() > 0) {
                    target.add(addHeadingPrefix(current.toString().trim(), headingPrefix));
                    current.setLength(0);
                }
                appendLongText(normalizedParagraph, headingPrefix, target);
                continue;
            }

            if (current.length() == 0) {
                current.append(normalizedParagraph);
                continue;
            }

            int nextLength = current.length() + separator.length() + normalizedParagraph.length();
            if (nextLength <= maxChunkSize) {
                current.append(separator).append(normalizedParagraph);
            } else {
                target.add(addHeadingPrefix(current.toString().trim(), headingPrefix));
                current.setLength(0);
                current.append(normalizedParagraph);
            }
        }

        if (current.length() > 0) {
            target.add(addHeadingPrefix(current.toString().trim(), headingPrefix));
        }
    }

    private List<String> splitParagraphs(String section) {
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inCodeFence = false;

        String[] lines = section.split("\n", -1);
        for (String line : lines) {
            if (!inCodeFence && isHeadingLine(line) && current.length() > 0) {
                paragraphs.add(current.toString().trim());
                current.setLength(0);
            }

            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);

            if (isCodeFenceLine(line)) {
                inCodeFence = !inCodeFence;
                continue;
            }

            if (!inCodeFence && line.trim().isEmpty()) {
                paragraphs.add(current.toString().trim());
                current.setLength(0);
            }
        }

        if (current.length() > 0) {
            paragraphs.add(current.toString().trim());
        }

        return paragraphs;
    }

    private void appendLongText(String text, String headingPrefix, List<String> target) {
        String separator = keepSeparator ? "\n" : " ";
        StringBuilder current = new StringBuilder();

        for (String sentence : splitSentences(text)) {
            if (!StringUtils.hasText(sentence)) {
                continue;
            }

            String normalizedSentence = sentence.trim();
            if (normalizedSentence.length() > maxChunkSize) {
                if (current.length() > 0) {
                    target.add(addHeadingPrefix(current.toString().trim(), headingPrefix));
                    current.setLength(0);
                }
                hardSplit(normalizedSentence, headingPrefix, target);
                continue;
            }

            if (current.length() == 0) {
                current.append(normalizedSentence);
                continue;
            }

            int nextLength = current.length() + separator.length() + normalizedSentence.length();
            if (nextLength <= maxChunkSize) {
                current.append(separator).append(normalizedSentence);
            } else {
                target.add(addHeadingPrefix(current.toString().trim(), headingPrefix));
                current.setLength(0);
                current.append(normalizedSentence);
            }
        }

        if (current.length() > 0) {
            target.add(addHeadingPrefix(current.toString().trim(), headingPrefix));
        }
    }

    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (char ch : text.toCharArray()) {
            current.append(ch);
            if (isSentenceBoundary(ch)) {
                String sentence = current.toString().trim();
                if (StringUtils.hasText(sentence)) {
                    sentences.add(sentence);
                }
                current.setLength(0);
            }
        }

        if (current.length() > 0) {
            String sentence = current.toString().trim();
            if (StringUtils.hasText(sentence)) {
                sentences.add(sentence);
            }
        }

        return sentences;
    }

    private void hardSplit(String text, String headingPrefix, List<String> target) {
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(index + maxChunkSize, text.length());
            String chunk = text.substring(index, end).trim();
            if (StringUtils.hasText(chunk)) {
                target.add(addHeadingPrefix(chunk, headingPrefix));
            }
            index = end;
        }
    }

    private List<String> mergeSmallChunks(List<String> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }

        String separator = keepSeparator ? "\n\n" : "\n";
        List<String> merged = new ArrayList<>();

        for (String chunk : chunks) {
            if (!StringUtils.hasText(chunk)) {
                continue;
            }
            String normalizedChunk = chunk.trim();

            if (merged.isEmpty()) {
                merged.add(normalizedChunk);
                continue;
            }

            int lastIndex = merged.size() - 1;
            String previous = merged.get(lastIndex);
            if (hasHeadingPathPrefix(previous) || hasHeadingPathPrefix(normalizedChunk)) {
                merged.add(normalizedChunk);
                continue;
            }

            int mergedLength = previous.length() + separator.length() + normalizedChunk.length();

            if ((previous.length() < minChunkSizeChars || normalizedChunk.length() < (minChunkSizeChars / 2))
                    && mergedLength <= maxChunkSize) {
                merged.set(lastIndex, previous + separator + normalizedChunk);
            } else {
                merged.add(normalizedChunk);
            }
        }

        if (merged.size() >= 2) {
            int lastIndex = merged.size() - 1;
            String last = merged.get(lastIndex);
            String previous = merged.get(lastIndex - 1);
            if (hasHeadingPathPrefix(previous) || hasHeadingPathPrefix(last)) {
                return merged;
            }

            int mergedLength = previous.length() + separator.length() + last.length();
            if (last.length() < minChunkSizeChars && mergedLength <= maxChunkSize) {
                merged.set(lastIndex - 1, previous + separator + last);
                merged.remove(lastIndex);
            }
        }

        return merged;
    }

    private boolean isHeadingLine(String line) {
        return HEADING_PATTERN.matcher(line).matches();
    }

    private boolean isCodeFenceLine(String line) {
        return CODE_FENCE_PATTERN.matcher(line).matches();
    }

    private void addSection(List<MarkdownSection> sections, String sectionText, List<String> headingPath) {
        String normalizedSection = sectionText == null ? "" : sectionText.trim();
        if (!StringUtils.hasText(normalizedSection)) {
            return;
        }

        sections.add(new MarkdownSection(List.copyOf(headingPath), normalizedSection));
    }

    private void updateHeadingStack(String[] headingStack, String headingLine) {
        int headingLevel = extractHeadingLevel(headingLine);
        String normalizedHeading = headingLine == null ? "" : headingLine.trim();

        if (headingLevel <= 0 || headingLevel > headingStack.length || !StringUtils.hasText(normalizedHeading)) {
            return;
        }

        headingStack[headingLevel - 1] = normalizedHeading;
        for (int i = headingLevel; i < headingStack.length; i++) {
            headingStack[i] = null;
        }
    }

    private List<String> snapshotHeadingPath(String[] headingStack) {
        List<String> path = new ArrayList<>();
        for (String heading : headingStack) {
            if (StringUtils.hasText(heading)) {
                path.add(heading.trim());
            }
        }
        return path;
    }

    private String buildHeadingPathPrefix(List<String> headingPath) {
        if (headingPath == null || headingPath.isEmpty()) {
            return "";
        }

        List<String> labels = new ArrayList<>();
        for (String headingLine : headingPath) {
            if (!StringUtils.hasText(headingLine)) {
                continue;
            }

            int level = extractHeadingLevel(headingLine);
            String label = headingLine.trim();
            if (level > 0 && label.length() > level) {
                label = label.substring(level).trim();
            }

            if (StringUtils.hasText(label)) {
                labels.add(label);
            }
        }

        if (labels.isEmpty()) {
            return "";
        }

        return HEADING_PATH_PREFIX + String.join(" > ", labels);
    }

    private String addHeadingPrefix(String chunk, String headingPrefix) {
        if (!StringUtils.hasText(chunk)) {
            return "";
        }

        String normalizedChunk = chunk.trim();
        if (!StringUtils.hasText(headingPrefix) || hasHeadingPathPrefix(normalizedChunk)) {
            return normalizedChunk;
        }

        return headingPrefix + "\n\n" + normalizedChunk;
    }

    private boolean hasHeadingPathPrefix(String chunk) {
        return StringUtils.hasText(chunk) && chunk.startsWith(HEADING_PATH_PREFIX);
    }

    private int extractHeadingLevel(String line) {
        if (!StringUtils.hasText(line)) {
            return 0;
        }

        int index = 0;
        int length = line.length();
        while (index < length && index < 3 && Character.isWhitespace(line.charAt(index))) {
            index++;
        }

        int level = 0;
        while (index < length && line.charAt(index) == '#') {
            level++;
            index++;
        }

        if (level < 1 || level > 6) {
            return 0;
        }

        if (index >= length || !Character.isWhitespace(line.charAt(index))) {
            return 0;
        }

        return level;
    }

    private boolean isSentenceBoundary(char ch) {
        return ch == '\n'
                || ch == '.'
                || ch == '!'
                || ch == '?'
                || ch == ';'
                || ch == '。'
                || ch == '！'
                || ch == '？'
                || ch == '；';
    }

    private record MarkdownSection(List<String> headingPath, String content) {
    }
}
