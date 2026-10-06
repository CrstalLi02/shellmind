package com.shellmind.trigger.http;

import com.shellmind.api.response.Response;
import com.shellmind.api.dto.PermissionResolveRequestDTO;
import com.shellmind.cases.policy.PermissionResolveCase;
import com.shellmind.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * Permission-confirm callback controller
 *
 * <p>After the user confirms/rejects in PermissionConfirmModal, POST /api/v1/permission/resolve
 * <p>The backend receives the result and wakes the ToolCallNode thread waiting in PermissionConfirmManager
 *
 * @author Teaching Edition - ShellMind
 * 2026/6/22
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/permission")
public class PermissionResolveController {

    @Resource
    private PermissionResolveCase permissionResolveCase;

    /**
     * Receive the user confirmation result
     */
    @PostMapping("/resolve")
    public Response<String> resolvePermission(@RequestBody PermissionResolveRequestDTO request) {
        log.info("Received permission-confirm callback: confirmId={}, approved={}", request.getConfirmId(), request.isApproved());

        // Record circuit-breaker state and wake the waiting thread
        permissionResolveCase.resolve(
                request.getConfirmId(),
                request.isApproved(),
                request.getModifiedArgs()
        );

        return Response.<String>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data("ok")
                .build();
    }
}
