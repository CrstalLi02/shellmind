package com.shellmind.trigger.http;

import com.shellmind.api.dto.ModelConfigRequestDTO;
import com.shellmind.api.dto.ModelConfigResponseDTO;
import com.shellmind.api.response.Response;
import com.shellmind.cases.llm.ModelConfigCase;
import com.shellmind.types.enums.ResponseCode;
import com.shellmind.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/model")
@CrossOrigin(origins = "*")
public class ModelConfigController {

    @Resource
    private ModelConfigCase modelConfigCase;

    @GetMapping("list")
    public Response<List<ModelConfigResponseDTO>> list() {
        try {
            List<ModelConfigResponseDTO> data = modelConfigCase.list();
            return Response.<List<ModelConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            return error("Failed to query model configs", e);
        }
    }

    @PostMapping("save")
    public Response<ModelConfigResponseDTO> save(@RequestBody ModelConfigRequestDTO requestDTO) {
        try {
            ModelConfigResponseDTO saved = modelConfigCase.save(requestDTO);
            return Response.<ModelConfigResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(saved)
                    .build();
        } catch (AppException e) {
            return Response.<ModelConfigResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            return error("Failed to save model config", e);
        }
    }

    @PostMapping("delete")
    public Response<Void> delete(@RequestParam("id") Long id) {
        try {
            modelConfigCase.delete(id);
            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (AppException e) {
            return Response.<Void>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            return error("Failed to delete model config", e);
        }
    }

    @PostMapping("reveal")
    public Response<String> revealApiKey(@RequestParam("id") Long id) {
        try {
            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(modelConfigCase.revealApiKey(id))
                    .build();
        } catch (AppException e) {
            return Response.<String>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            return error("Failed to reveal model API key", e);
        }
    }

    @PostMapping("test")
    public Response<String> test(@RequestBody ModelConfigRequestDTO requestDTO) {
        try {
            String result = modelConfigCase.test(requestDTO);
            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("Connected successfully")
                    .data(result)
                    .build();
        } catch (AppException e) {
            return Response.<String>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            return error("Failed to test model connection", e);
        }
    }

    private <T> Response<T> error(String message, Exception e) {
        log.error(message, e);
        return Response.<T>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(message)
                .build();
    }
}
