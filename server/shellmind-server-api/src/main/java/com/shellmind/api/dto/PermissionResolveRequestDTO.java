package com.shellmind.api.dto;

import lombok.Data;

/**
 * High-risk operation confirmation result
 */
@Data
public class PermissionResolveRequestDTO {
    private String confirmId;
    private boolean approved;
    private String modifiedArgs;
}
