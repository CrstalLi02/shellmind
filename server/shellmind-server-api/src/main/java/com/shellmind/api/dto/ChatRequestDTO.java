package com.shellmind.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatRequestDTO {

    private String agentId;
    private String userId;
    private String sessionId;
    private String message;

    /**
     * Request-level model config ID; when empty, keep using the default assembled model
     */
    private Long modelId;

    /**
     * SSH terminal session ID (used when the agent runs commands)
     * If omitted, the system tries to resolve it from the session binding
     */
    private String terminalSessionId;

    /**
     * Current project context (injected by the frontend so dynamic prompts can identify the open project)
     */
    private ProjectContextDTO projectContext;

    /**
     * Inline image data (base64), for multimodal input
     * Images uploaded by the frontend are passed to the AI model through this field
     */
    private List<InlineData> inlineDatas;

    /**
     * Inline data (images and other binary content)
     */
    @Data
    public static class InlineData {
        /**
         * Base64-encoded data (without the data:image/xxx;base64, prefix)
         */
        private String data;
        /**
         * MIME type, e.g. image/png or image/jpeg
         */
        private String mimeType;
    }

}
