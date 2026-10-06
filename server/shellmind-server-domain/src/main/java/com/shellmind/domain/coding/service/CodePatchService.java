package com.shellmind.domain.coding.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class CodePatchService {

    public CodePatchResult writeWithBackup(Path target, String content) throws IOException {
        Path backupPath = null;
        String originalContent = "";

        if (Files.exists(target)) {
            originalContent = Files.readString(target, StandardCharsets.UTF_8);
            backupPath = target.resolveSibling("." + target.getFileName() + ".shellmind."
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) + ".bak");
            Files.writeString(backupPath, originalContent, StandardCharsets.UTF_8);
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temporaryFile = Files.createTempFile(parent, target.getFileName().toString(), ".shellmind.tmp");
        Files.writeString(temporaryFile, content == null ? "" : content, StandardCharsets.UTF_8);
        try {
            Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING);
        }

        String unifiedDiff = buildUnifiedDiff(target.toString(), originalContent, content == null ? "" : content);
        return new CodePatchResult(unifiedDiff, backupPath == null ? null : backupPath.toString());
    }

    public String buildUnifiedDiff(String path, String before, String after) {
        List<String> beforeLines = toLines(before);
        List<String> afterLines = toLines(after);
        List<String> diffLines = new ArrayList<>();
        diffLines.add("--- a/" + path);
        diffLines.add("+++ b/" + path);

        int beforeIndex = 0;
        int afterIndex = 0;
        int beforeLine = 1;
        int afterLine = 1;

        while (beforeIndex < beforeLines.size() || afterIndex < afterLines.size()) {
            if (beforeIndex >= beforeLines.size()) {
                diffLines.add("+" + afterLines.get(afterIndex));
                afterIndex++;
                afterLine++;
            } else if (afterIndex >= afterLines.size()) {
                diffLines.add("-" + beforeLines.get(beforeIndex));
                beforeIndex++;
                beforeLine++;
            } else if (beforeLines.get(beforeIndex).equals(afterLines.get(afterIndex))) {
                diffLines.add(" " + beforeLines.get(beforeIndex));
                beforeIndex++;
                afterIndex++;
                beforeLine++;
                afterLine++;
            } else if (beforeLines.size() - beforeIndex > afterLines.size() - afterIndex) {
                diffLines.add("-" + beforeLines.get(beforeIndex));
                beforeIndex++;
                beforeLine++;
            } else {
                diffLines.add("+" + afterLines.get(afterIndex));
                afterIndex++;
                afterLine++;
            }
        }

        return String.join("\n", diffLines);
    }

    public boolean rollback(Path target, String backupPath) throws IOException {
        Path backup = Path.of(backupPath);
        if (!Files.exists(backup)) {
            return false;
        }
        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private List<String> toLines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(List.of(value.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    public record CodePatchResult(String unifiedDiff, String backupPath) {
    }
}
