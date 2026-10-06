package com.shellmind.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ModelConfigResponseDTO {

    private Long id;
    private String name;
    private String baseUrl;
    private String modelName;
    private String completionsPath;
    private Boolean hasApiKey;
    private String apiKeyMasked;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
