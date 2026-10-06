package com.shellmind.domain.coding.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodePatchServiceTests {

    private final CodePatchService codePatchService = new CodePatchService();

    @Test
    void writeWithBackupCreatesDiffAndBackup() throws IOException {
        Path file = Files.createTempFile("shellmind", ".txt");
        Files.writeString(file, "line-1\n");

        CodePatchService.CodePatchResult result =
                codePatchService.writeWithBackup(file, "line-1\nline-2\n");

        assertEquals("line-1\nline-2\n", Files.readString(file));
        assertNotNull(result.backupPath());
        assertTrue(result.unifiedDiff().contains("+line-2"));
        assertTrue(Files.exists(Path.of(result.backupPath())));
    }

    @Test
    void rollbackRestoresOriginalContent() throws IOException {
        Path file = Files.createTempFile("shellmind", ".txt");
        Files.writeString(file, "before\n");
        CodePatchService.CodePatchResult result =
                codePatchService.writeWithBackup(file, "after\n");

        assertTrue(codePatchService.rollback(file, result.backupPath()));
        assertEquals("before\n", Files.readString(file));
    }
}
