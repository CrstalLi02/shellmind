package com.shellmind.domain.llm.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ModelConfigEntity {

    private Long id;

    private String name;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private String completionsPath;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
