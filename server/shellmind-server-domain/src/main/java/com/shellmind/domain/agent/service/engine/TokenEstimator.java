package com.shellmind.domain.agent.service.engine;

/**
 * Token estimator (distinguishes CJK vs Latin so mixed-language error stays bounded).
 *
 * Estimation rules:
 * - CJK (Chinese/Japanese/Korean): 1 character ≈ 1.5 tokens
 * - Latin / ASCII: 1 token ≈ 4 characters
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static long estimate(String text) {
        if (text == null || text.isEmpty()) return 0;

        int cjkChars = 0;
        int otherChars = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }

        // CJK: 1.5 token/char; Latin: 1 token/4 chars
        return Math.round(cjkChars * 1.5 + otherChars / 4.0);
    }

    public static long estimateMessages(java.util.List<java.util.Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        long total = 0;
        for (java.util.Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            total += estimate(content != null ? String.valueOf(content) : "");
        }
        return total;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK Unified
                || (c >= 0x3400 && c <= 0x4DBF) // CJK Extension A
                || (c >= 0x3040 && c <= 0x30FF) // Hiragana / Katakana
                || (c >= 0xAC00 && c <= 0xD7AF); // Hangul
    }
}
