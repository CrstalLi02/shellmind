package com.shellmind.trigger.http;

import com.shellmind.api.response.Response;
import com.shellmind.api.dto.CodePatchResponseDTO;
import com.shellmind.cases.coding.CodePatchCase;
import com.shellmind.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/patch")
public class CodePatchController {

    @Resource
    private CodePatchCase codePatchCase;

    @GetMapping("/{patchId}")
    public Response<CodePatchResponseDTO> queryPatch(@PathVariable String patchId) {
        CodePatchResponseDTO patch = codePatchCase.queryPatch(patchId).orElse(null);
        if (patch == null) {
            return Response.<CodePatchResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Patch does not exist")
                    .build();
        }
        return success(patch);
    }

    @GetMapping("/run/{runId}")
    public Response<List<CodePatchResponseDTO>> queryByRun(@PathVariable String runId) {
        List<CodePatchResponseDTO> patches = codePatchCase.queryByRun(runId);
        return success(patches);
    }

    @GetMapping("/session/{sessionId}")
    public Response<List<CodePatchResponseDTO>> queryBySession(
            @PathVariable String sessionId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<CodePatchResponseDTO> patches = codePatchCase.queryBySession(sessionId, limit);
        return success(patches);
    }

    @GetMapping("/session/{sessionId}/unapproved")
    public Response<List<CodePatchResponseDTO>> queryUnapproved(
            @PathVariable String sessionId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<CodePatchResponseDTO> patches = codePatchCase.queryUnapproved(sessionId, limit);
        return success(patches);
    }

    @PostMapping("/{patchId}/rollback")
    public Response<String> rollback(@PathVariable String patchId) {
        boolean success = codePatchCase.rollback(patchId);
        return Response.<String>builder()
                .code(success ? ResponseCode.SUCCESS.getCode() : ResponseCode.UN_ERROR.getCode())
                .info(success ? "Rolled back" : "Rollback failed (not found or status not allowed)")
                .data(success ? "ok" : null)
                .build();
    }

    @PostMapping("/run/{runId}/rollback")
    public Response<String> rollbackRun(@PathVariable String runId) {
        int rolled = codePatchCase.rollbackRun(runId);
        return Response.<String>builder()
                .code(rolled > 0 ? ResponseCode.SUCCESS.getCode() : ResponseCode.UN_ERROR.getCode())
                .info("Rolled back " + rolled + " file(s)")
                .data(String.valueOf(rolled))
                .build();
    }

    @PostMapping("/{patchId}/approve")
    public Response<String> approve(@PathVariable String patchId) {
        boolean approved = codePatchCase.approve(patchId);
        return Response.<String>builder()
                .code(approved ? ResponseCode.SUCCESS.getCode() : ResponseCode.UN_ERROR.getCode())
                .info(approved ? "ok" : "Current status cannot be approved")
                .data(approved ? "ok" : null)
                .build();
    }

    @PostMapping("/{patchId}/reject")
    public Response<String> reject(@PathVariable String patchId) {
        boolean rolled = codePatchCase.reject(patchId);
        return Response.<String>builder()
                .code(rolled ? ResponseCode.SUCCESS.getCode() : ResponseCode.UN_ERROR.getCode())
                .info(rolled ? "Rejected and rolled back" : "Reject failed")
                .build();
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }
}
