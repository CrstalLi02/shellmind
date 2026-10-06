package com.shellmind.infrastructure.agent.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MyTestMcpService {

    @Tool(description = "Convert lowercase letters to uppercase")
    public XxxResponse toUpperCase(XxxRequest request) {
        XxxResponse xxxResponse = new XxxResponse();
        xxxResponse.setContent(request.getWord().toUpperCase());
        return xxxResponse;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XxxRequest {
        @JsonProperty(required = true, value = "word")
        @JsonPropertyDescription("English word, string, or letters. For example: good, xiaofuge")
        private String word;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XxxResponse {
        @JsonProperty(required = true, value = "content")
        @JsonPropertyDescription("Word conversion result")
        private String content;
    }

}
