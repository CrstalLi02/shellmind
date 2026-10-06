package com.shellmind.api.dto;

import lombok.Data;

@Data
public class ModelConfigRequestDTO {

    private Long id;
    private String name;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private String completionsPath;
}
