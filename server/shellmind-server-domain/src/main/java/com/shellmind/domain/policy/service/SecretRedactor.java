package com.shellmind.domain.policy.service;

import java.util.regex.Pattern;

/**
 * Secret redactor: strips API keys, passwords, tokens, and similar secrets from tool results and logs.
 */
public final class SecretRedactor {

    private static final Pattern[] SECRET_PATTERNS = {
            Pattern.compile("(?i)(api[_-]?key|apikey|secret|token|password|passwd|pwd)\\s*[=:]\\s*['\"]?[\\w\\-./+=]{8,}"),
            Pattern.compile("sk-[a-zA-Z0-9]{20,}"),
            Pattern.compile("ghp_[a-zA-Z0-9]{36,}"),
            Pattern.compile("gho_[a-zA-Z0-9]{36,}"),
            Pattern.compile("xox[bpars]-[a-zA-Z0-9\\-]{10,}"),
            Pattern.compile("-----BEGIN\\s+(RSA\\s+)?PRIVATE\\s+KEY-----[\\s\\S]*?-----END\\s+(RSA\\s+)?PRIVATE\\s+KEY-----"),
            Pattern.compile("(?i)bearer\\s+[\\w\\-./+=]{20,}"),
    };

    private static final String REDACTED = "[REDACTED]";

    private SecretRedactor() {}

    public static String redact(String input) {
        if (input == null || input.isEmpty()) return input;
        String result = input;
        for (Pattern pattern : SECRET_PATTERNS) {
            result = pattern.matcher(result).replaceAll(REDACTED);
        }
        return result;
    }
}
