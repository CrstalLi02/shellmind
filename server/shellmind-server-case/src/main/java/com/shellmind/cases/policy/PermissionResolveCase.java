package com.shellmind.cases.policy;

import com.shellmind.cases.react.PermissionConfirmManager;
import com.shellmind.domain.policy.service.PermissionGuard;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * Permission-confirm callback use case: record circuit-breaker state and wake the tool call waiting for the user.
 */
@Service
public class PermissionResolveCase {

    @Resource
    private PermissionGuard permissionGuard;

    @Resource
    private PermissionConfirmManager permissionConfirmManager;

    public void resolve(String confirmId, boolean approved, String modifiedArgs) {
        permissionGuard.recordConfirmation("default", approved);
        permissionConfirmManager.resolve(confirmId, approved, modifiedArgs);
    }
}
