package com.shellmind.domain.coding.service.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostEditVerificationResultTests {

    @Test
    void extractMavenErrorSummary() {
        String output = """
                [INFO] Scanning for projects...
                [ERROR] COMPILATION ERROR :
                [ERROR] /src/Main.java:[10,5] cannot find symbol
                [ERROR]   symbol: variable foo
                [INFO] BUILD FAILURE
                [INFO] Total time: 2.1 s
                """;

        PostEditVerificationService.VerificationResult result =
                PostEditVerificationService.VerificationResult.failed("maven", "exit code: 1", output);

        assertFalse(result.verified());
        assertEquals("maven", result.projectType());
        assertTrue(result.errorSummary().contains("cannot find symbol"));
        assertTrue(result.errorSummary().contains("[ERROR]"));
    }

    @Test
    void extractNpmErrorSummary() {
        String output = """
                npm ERR! code ELIFECYCLE
                npm ERR! errno 1
                npm ERR! test failed
                """;

        PostEditVerificationService.VerificationResult result =
                PostEditVerificationService.VerificationResult.failed("npm", "exit code: 1", output);

        assertTrue(result.errorSummary().contains("npm ERR!"));
    }

    @Test
    void noErrorLinesReturnsNullSummary() {
        String output = "plain output without errors\nanother line\n";

        PostEditVerificationService.VerificationResult result =
                PostEditVerificationService.VerificationResult.failed("maven", "exit code: 1", output);

        assertNull(result.errorSummary());
    }

    @Test
    void emptyOutputReturnsNullSummary() {
        PostEditVerificationService.VerificationResult result =
                PostEditVerificationService.VerificationResult.failed("maven", "exit code: 1", "");

        assertNull(result.errorSummary());
    }

    @Test
    void passedResultHasNoErrorSummary() {
        PostEditVerificationService.VerificationResult result =
                PostEditVerificationService.VerificationResult.passed("maven", "BUILD SUCCESS");

        assertTrue(result.verified());
        assertNull(result.errorSummary());
    }

    @Test
    void skippedResultIsCorrectlyMarked() {
        PostEditVerificationService.VerificationResult result =
                PostEditVerificationService.VerificationResult.skipped("No project path provided");

        assertFalse(result.verified());
        assertTrue(result.skipped());
        assertEquals("unknown", result.projectType());
    }
}
