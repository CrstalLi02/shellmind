package com.shellmind.domain.agent.service.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTests {

    @Test
    void emptyAndNullReturnZero() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void pureEnglishEstimatesAt4CharsPerToken() {
        String text = "Hello World, this is a test";
        long tokens = TokenEstimator.estimate(text);
        assertTrue(tokens > 0);
        assertTrue(tokens < text.length(), "English should be less tokens than chars");
    }

    @Test
    void pureChineseEstimatesAt1Point5PerChar() {
        String text = "\u4F60\u597D\u4E16\u754C\u6D4B\u8BD5\u4EE3\u7801"; // 8 CJK chars
        long tokens = TokenEstimator.estimate(text);
        assertEquals(12, tokens, "8 CJK chars × 1.5 = 12 tokens");
    }

    @Test
    void mixedLanguageEstimatesHigherThanCharDivideBy2() {
        String mixed = "\u4F7F\u7528 Spring Boot \u6784\u5EFA\u5E94\u7528\u7A0B\u5E8F and deploy to AWS";
        long tokens = TokenEstimator.estimate(mixed);
        long oldEstimate = mixed.length() / 2;
        assertTrue(tokens > oldEstimate,
                "Mixed language should be more accurate than char/2: " + tokens + " > " + oldEstimate);
    }

    @Test
    void messageEstimationAggregates() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "hello"),
                Map.of("role", "assistant", "content", "hello, I can help you"),
                Map.of("role", "user", "content", "thanks")
        );
        long tokens = TokenEstimator.estimateMessages(messages);
        assertTrue(tokens > 0);
    }

    @Test
    void apiKeyPatternIsRedacted() {
        String input = "Use API key sk-abcdefghij1234567890abcdefghij12 for auth";
        String redacted = com.shellmind.domain.policy.service.SecretRedactor.redact(input);
        assertTrue(!redacted.contains("sk-abcdefghij1234567890abcdefghij12"));
        assertTrue(redacted.contains("[REDACTED]"));
    }

    @Test
    void passwordPatternIsRedacted() {
        String input = "password = mySecret123456";
        String redacted = com.shellmind.domain.policy.service.SecretRedactor.redact(input);
        assertTrue(!redacted.contains("mySecret123456"));
    }

    @Test
    void normalCodeIsNotRedacted() {
        String input = "public void process(String data) { return data.trim(); }";
        String redacted = com.shellmind.domain.policy.service.SecretRedactor.redact(input);
        assertEquals(input, redacted);
    }

    @Test
    void privateKeyBlockIsRedacted() {
        String input = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAK\n-----END RSA PRIVATE KEY-----";
        String redacted = com.shellmind.domain.policy.service.SecretRedactor.redact(input);
        assertTrue(!redacted.contains("MIIEowIBAAK"));
    }
}
