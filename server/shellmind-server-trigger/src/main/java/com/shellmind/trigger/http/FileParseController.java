package com.shellmind.trigger.http;

import com.shellmind.api.dto.SshFileContentResponseDTO;
import com.shellmind.api.response.Response;
import com.shellmind.cases.file.FileParseCase;
import com.shellmind.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.Base64;
import java.util.Map;

/**
 * Generic file-parse API (not the SSH path).
 * <p>
 * Used by the desktop app for local file preview: the client reads bytes, uploads base64,
 * and the server returns a structured text view. Currently supports .class bytecode files.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/file")
@CrossOrigin(origins = "*")
public class FileParseController {

    /** Max parse size in bytes (about 21MB before base64 decode) */
    private static final int MAX_PARSE_BYTES = 16 * 1024 * 1024;

    @Resource
    private FileParseCase fileParseCase;

    @RequestMapping(value = "parse-class", method = RequestMethod.POST)
    public Response<SshFileContentResponseDTO> parseClass(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "unknown.class");
        String contentBase64 = body.getOrDefault("contentBase64", "");
        try {
            if (contentBase64.isBlank()) {
                return Response.<SshFileContentResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info("Content cannot be empty").build();
            }
            byte[] bytes = Base64.getDecoder().decode(contentBase64);
            if (bytes.length > MAX_PARSE_BYTES) {
                return Response.<SshFileContentResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info("File is too large to parse (16MB limit)").build();
            }
            if (!fileParseCase.isClassFile(bytes)) {
                return Response.<SshFileContentResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info("Not a valid class file").build();
            }
            String text = fileParseCase.parseClass(bytes, name);
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                    .data(SshFileContentResponseDTO.builder()
                            .path(name).name(name).charset("UTF-8")
                            .size((long) bytes.length)
                            .binary(false).truncated(false)
                            .content(text).parsedType("java-class").build())
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("Exception while parsing class file name={}", name, e);
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info("Failed to parse class file: " + e.getMessage()).build();
        }
    }
}
