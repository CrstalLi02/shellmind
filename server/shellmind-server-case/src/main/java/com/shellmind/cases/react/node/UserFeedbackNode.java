package com.shellmind.cases.react.node;

import com.shellmind.api.dto.ChatRequestDTO;
import com.shellmind.api.dto.ReActEventDTO;
import com.shellmind.api.dto.ReActResultDTO;
import com.shellmind.cases.react.AbstractAIAgentReActSupport;
import com.shellmind.cases.react.factory.DefaultReActFactory;
import com.shellmind.types.design.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.shellmind.domain.agent.adapter.port.ClientChannel;

/**
 * ReAct user-feedback node (send the result + cleanup).
 *
 * <p>Responsibilities:
 * 1. Build the final ReActResultDTO
 * 2. Send a done SSE event
 * 3. Close the client channel
 *
 * <p>This is the terminal node of the ReAct loop chain. It:
 * - wraps the accumulated response text as the final result
 * - notifies the frontend via an SSE done event
 * <p>Run-context unregister is completed by AiCallNode in its finally block when the run ends.
 *
 * @author xiaofuge bugstack.cn
 * 2026/5/4
 */
@Slf4j
@Component("reactUserFeedbackNode")
public class UserFeedbackNode extends AbstractAIAgentReActSupport {

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct UserFeedbackNode - sending final result");

        ClientChannel emitter = dynamicContext.getEmitter();

        try {
            // 1. Build the final result
            ReActResultDTO result = buildFinalResult(dynamicContext);

            // 2. Send the done event only while the connection is still valid
            if (!dynamicContext.isCancelled()) {
                sendDoneEvent(emitter, result, dynamicContext);
            }

            // 3. Close the emitter (ignore already-closed exceptions)
            try {
                emitter.complete();
            } catch (Exception alreadyCompleted) {
                log.debug("emitter already completed; ignoring close exception");
            }

            log.info("ReAct complete - steps: {}, toolCalls: {}, stopReason: {}",
                    result.getTotalSteps(),
                    result.getTotalToolCalls(),
                    result.getStopReason() != null ? result.getStopReason() : "completed");

            return result;

        } catch (Exception e) {
            log.error("ReAct UserFeedbackNode send failed", e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
            throw e;
        } finally {
            // 4. Clean up context
            cleanup(dynamicContext);
        }
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        // terminal node, no further routing
        return StrategyHandler.DEFAULT;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Build the final result
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build the final result DTO
     */
    private ReActResultDTO buildFinalResult(DefaultReActFactory.DynamicContext dynamicContext) {
        String assistantText = dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString()
                : "";
        int boundary = Math.min(
                Math.max(0, dynamicContext.getAssistantContentBoundary()),
                assistantText.length()
        );
        String fullText = assistantText.substring(boundary);
        boolean hasFileMutation = resultContainsFileMutation(dynamicContext);

        fullText = removeRepeatedAnswer(fullText);
        // Full Markdown normalize (heading/list/table line-break repair + bold spacing + blank-line compression)
        String beforeNormalize = fullText;
        fullText = com.shellmind.cases.react.util.MarkdownNormalizer.normalize(fullText);
        if (fullText == null || fullText.isBlank()) {
            String reason = dynamicContext.getStopReason();
            if ("error".equals(reason)) {
                // Error stop: give a brief, accurate error (from DynamicContext.errorMessage) so the user can act on it
                fullText = buildErrorNotice(dynamicContext.getErrorMessage());
            } else {
                fullText = buildEmptyResultNotice(reason);
            }
        } else if ("error".equals(dynamicContext.getStopReason())) {
            // Already have a partial answer but stopped on error: append the error meaning at the end
            String errorNotice = buildErrorNotice(dynamicContext.getErrorMessage());
            if (!errorNotice.isBlank() && !fullText.contains(errorNotice)) {
                fullText = fullText + "\n\n---\n⚠️ " + errorNotice;
            }
        }
        if (!hasFileMutation) {
            fullText = removeReadOnlyChangeSummary(fullText);
            // Fallback: when this run made no tool calls, drop model-added disclaimers such as "no tools needed / did not modify files"
            if (dynamicContext.getStep() == 0 || totalToolCalls(dynamicContext) == 0) {
                fullText = removeNoToolDisclaimer(fullText);
            }
        }
        log.info("[normalize] beforeLen={}, afterLen={}, before={}, after={}",
                beforeNormalize.length(), fullText.length(),
                beforeNormalize.length() > 80 ? beforeNormalize.substring(0, 80) : beforeNormalize,
                fullText.length() > 80 ? fullText.substring(0, 80) : fullText);

        String stopReason = dynamicContext.getStopReason();
        if (stopReason == null) {
            stopReason = "completed";
        }

        return ReActResultDTO.builder()
                .content(fullText)
                .totalSteps(dynamicContext.getStep())
                .totalToolCalls(dynamicContext.getResult() != null ? dynamicContext.getResult().getTotalToolCalls() : 0)
                .maxStepsReached("max_steps".equals(stopReason))
                .userStopped("user_stop".equals(stopReason))
                .idleTimeout("idle_timeout".equals(stopReason))
                .stopReason(stopReason)
                .toolCalls(dynamicContext.getCurrentToolCalls())
                .toolResults(dynamicContext.getCurrentToolResults())
                .error("error".equals(stopReason) ? dynamicContext.getErrorMessage() : null)
                .build();
    }

    private String removeRepeatedAnswer(String text) {
        if (text == null || text.length() < 80) {
            return text;
        }

        String normalized = text.replace("\r\n", "\n").trim();
        String[] lines = normalized.split("\n", -1);
            int meaningfulLines = 0;
            int meaningfulCharacters = 0;
            boolean repeated = true;
        for (int blockLength = lines.length / 2; blockLength >= 2; blockLength--) {
            for (int previousStart = lines.length - blockLength * 2; previousStart >= 0; previousStart--) {
                meaningfulLines = 0;
                meaningfulCharacters = 0;
                repeated = true;
                for (int index = 0; index < blockLength; index++) {
                    String previousLine = lines[previousStart + index].trim();
                    String currentLine = lines[lines.length - blockLength + index].trim();
                    if (!previousLine.equals(currentLine)) {
                        repeated = false;
                        break;
                    }
                    if (!previousLine.isEmpty()) {
                        meaningfulLines++;
                        meaningfulCharacters += previousLine.length();
                    }
                }

                if (repeated && meaningfulLines >= 2 && meaningfulCharacters >= 80) {
                    return String.join("\n", java.util.Arrays.copyOfRange(lines, 0, lines.length - blockLength)).trim();
                }
            }
        }
        return text;
    }

    private String buildEmptyResultNotice(String stopReason) {
        return switch (stopReason == null ? "completed" : stopReason) {
            case "diminishing_returns" -> "Automatically stopped: the same tool sequence ran for several rounds. Add more information or adjust the task before continuing.";
            case "idle_timeout" -> "Automatically stopped: idle timeout.";
            case "max_steps" -> "Stopped: maximum step count reached.";
            case "max_tool_calls" -> "Stopped: maximum tool-call count reached.";
            case "user_stop" -> "Stopped at your request.";
            case "error" -> "Execution terminated abnormally. Check the error details or retry.";
            default -> "The current run finished, but the model did not return a final summary.";
        };
    }

    /**
     * Build a brief, actionable error notice when stopping on error.
     * Extract the key error from errorMessage (HTTP status / connection exceptions, etc.); do not dump a full stack.
     */
    private String buildErrorNotice(String errorMessage) {
        String brief = extractBriefError(errorMessage);
        StringBuilder sb = new StringBuilder("Execution terminated abnormally");
        if (!brief.isBlank()) {
            sb.append(": ").append(brief);
        }
        sb.append("\nYou can wait a moment and retry; if it keeps failing, check the model settings (API URL / key / quota).");
        return sb.toString();
    }

    /**
     * Extract a one-line summary from the original error:
     * - Prefer recognizing HTTP status codes (429 rate-limit / 401 auth / 5xx server errors) and give a matching action
     * - Connection-class exceptions get a network/address hint
     * - Fallback: take the first meaningful exception line, filtering Java stack frames
     */
    private String extractBriefError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "";
        }
        String msg = errorMessage.replace("\r\n", "\n").trim();
        String lower = msg.toLowerCase();

        // 1. HTTP status-code class (incl. 429 Too Many Requests / 401 Unauthorized, etc.)
        java.util.regex.Matcher statusMatcher = java.util.regex.Pattern
                .compile("(\\d{3})\\s+(Too Many Requests|Unauthorized|Forbidden|Not Found|Bad Request|Internal Server Error|Bad Gateway|Service Unavailable|Payment Required|Request Timeout)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(msg);
        if (statusMatcher.find()) {
            String code = statusMatcher.group(1);
            String phrase = statusMatcher.group(2).toLowerCase();
            String target = extractApiHost(msg);
            String withTarget = target.isBlank() ? "" : " (" + target + ")";
            return switch (code) {
                case "429" -> String.format("Model API rate-limited%s: too many requests or insufficient quota. Retry later, or check the model-service quota.", withTarget);
                case "401", "403" -> String.format("Model API authentication failed%s: check that the API key in settings is correct and valid.", withTarget);
                case "402" -> String.format("Model API balance is insufficient%s: top up the account or switch models.", withTarget);
                case "404" -> String.format("Model API URL was not found%s: check the Base URL and model ID in settings.", withTarget);
                case "408", "timeout" -> String.format("Model API request timed out%s: please retry later.", withTarget);
                default -> {
                    if (code.startsWith("5")) {
                        yield String.format("Model API server error%s (%s): retry later, or check the model-service status.", withTarget, phrase);
                    }
                    yield String.format("Model API returned %s %s%s.", code, phrase, withTarget);
                }
            };
        }
        if (lower.contains("rate limited") || lower.contains("too many requests")) {
            return "Model API rate-limited: too many requests or insufficient quota. Please retry later.";
        }

        // 2. Connection-class exceptions
        if (lower.contains("connection refused")) {
            String target = extractApiHost(msg);
            return String.format("Unable to reach the model API%s: the service is down or the URL is unreachable. Check the API URL in settings.", target.isBlank() ? "" : " (" + target + ")");
        }
        if (lower.contains("connect timed out") || lower.contains("connection timed out")) {
            return "Timed out connecting to the model API: check the network or whether the API URL is reachable.";
        }
        if (lower.contains("unknownhost") || lower.contains("no route to host")) {
            return "The model API host could not be resolved: check the API URL in settings.";
        }

        // 3. Fallback: take the first meaningful exception line (skip "at " stack frames / Caused-by prefix chains)
        for (String line : msg.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("at ")) continue;
            trimmed = trimmed.replaceFirst("^(?:java\\.lang\\.)?\\w+(?:\\.\\w+)*:\\s*", "");
            if (trimmed.isBlank()) continue;
            return trimmed.length() > 160 ? trimmed.substring(0, 160) + "…" : trimmed;
        }
        return "";
    }

    /**
     * Extract the API host from the error message (e.g. https://api.taotoken.net) so the user can tell which model service failed.
     */
    private String extractApiHost(String msg) {
        java.util.regex.Matcher urlMatcher = java.util.regex.Pattern
                .compile("https?://[\\w.-]+")
                .matcher(msg);
        return urlMatcher.find() ? urlMatcher.group() : "";
    }

    private boolean resultContainsFileMutation(DefaultReActFactory.DynamicContext dynamicContext) {
        if (dynamicContext.getCurrentToolResults() == null) return false;
        return dynamicContext.getCurrentToolResults().stream()
                .map(result -> String.valueOf(result.getOrDefault("name", result.getOrDefault("toolName", ""))))
                .map(name -> name.toLowerCase())
                .anyMatch(name -> name.contains("write")
                        || name.contains("create")
                        || name.contains("delete")
                        || name.contains("remove")
                        || name.contains("edit")
                        || name.contains("rollback"));
    }

    private int totalToolCalls(DefaultReActFactory.DynamicContext dynamicContext) {
        Integer total = dynamicContext.getResult() != null ? dynamicContext.getResult().getTotalToolCalls() : null;
        if (total != null) {
            return total;
        }
        return dynamicContext.getCurrentToolCalls() != null ? dynamicContext.getCurrentToolCalls().size() : 0;
    }

    /**
     * Strip model-added disclaimers in tool-free Q&A, such as
     * "no tools needed, and no files were modified", "(did not call tools)",
     * or "no files were modified this time".
     */
    private String removeNoToolDisclaimer(String text) {
        if (text == null || text.isBlank()) return text;
        return text
                .replaceAll("(?m)^\\s*[(\\uFF08]?\\s*(?:no tools needed|no need to call tools|did not call tools|did not call any tools)[^\\n.!?\\u3002\\uFF01\\uFF1F]*[)\\uFF09]?[.!?\\u3002\\uFF01\\uFF1F]?\\s*$", "")
                .replaceAll("(?m)^\\s*[(\\uFF08]?\\s*(?:this time )?(?:did not modify|did not change)(?: any)? files?[^\\n.!?\\u3002\\uFF01\\uFF1F]*[)\\uFF09]?[.!?\\u3002\\uFF01\\uFF1F]?\\s*$", "")
                .replaceAll("(?m)^\\s*[(\\uFF08]?\\s*no tools needed[,\\uFF0C\\u3001\\s]*(?:and )?(?:did not modify|did not change)(?: any)? files?[^\\n.!?\\u3002\\uFF01\\uFF1F]*[)\\uFF09]?[.!?\\u3002\\uFF01\\uFF1F]?\\s*$", "")
                .replaceAll("(?m)^\\s*[（(]?\\s*(?:无需|无需调用|没有调用|未调用)(?:调用)?工具[^\\n。！？]*[）)]?[。！]?\\s*$", "")
                .replaceAll("(?m)^\\s*[（(]?\\s*(?:本次)?(?:未修改|没有修改|未变更|没有变更)(?:任何)?文件[^\\n。！？]*[）)]?[。！]?\\s*$", "")
                .replaceAll("(?m)^\\s*[（(]?\\s*无需调用工具[，,、\\s]*(?:也)?(?:未修改|没有修改)(?:任何)?文件[^\\n。！？]*[）)]?[。！]?\\s*$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String removeReadOnlyChangeSummary(String text) {
        if (text == null || text.isBlank()) return "";
        String[] lines = text.split("\\r?\\n");
        java.util.List<String> kept = new java.util.ArrayList<>();
        boolean removing = false;
        for (String line : lines) {
            String normalized = line.trim();
            if (normalized.startsWith("📋") && (normalized.contains("Change summary") || normalized.contains("改动摘要") || normalized.contains("变更摘要"))) {
                removing = true;
                continue;
            }
            if (removing && (normalized.startsWith("#") || normalized.startsWith("📋"))) {
                removing = false;
            }
            if (!removing) kept.add(line);
        }
        return String.join("\n", kept).replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * Clean up context resources.
     */
    private void cleanup(DefaultReActFactory.DynamicContext dynamicContext) {
        try {
            // Run context is already unregistered from AgentRunRegistry; no extra thread or static-state cleanup here
            String sessionId = dynamicContext.getSessionId();
            log.debug("ReAct context cleanup complete sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("ReAct context cleanup exception: {}", e.getMessage());
        }
    }

}
