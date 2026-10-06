package com.shellmind.trigger.http;

import com.shellmind.api.ISshFileService;
import com.shellmind.api.dto.SshFileContentResponseDTO;
import com.shellmind.api.dto.SshFileTreeResponseDTO;
import com.shellmind.api.response.Response;
import com.shellmind.cases.ssh.SshFileCase;
import com.shellmind.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/ssh/file")
@CrossOrigin(origins = "*")
public class SshFileController implements ISshFileService {

    @Resource
    private SshFileCase sshFileCase;

    @RequestMapping(value = "tree", method = RequestMethod.GET)
    @Override
    public Response<SshFileTreeResponseDTO> tree(@RequestParam("connectionId") String connectionId,
                                                 @RequestParam(value = "path", required = false) String path) {
        try {
            SshFileTreeResponseDTO tree = sshFileCase.tree(connectionId, path);
            return Response.<SshFileTreeResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                    .data(tree).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<SshFileTreeResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("Exception while listing directory connectionId={} path={}", connectionId, path, e);
            return Response.<SshFileTreeResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info("Failed to list directory: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "content", method = RequestMethod.GET)
    @Override
    public Response<SshFileContentResponseDTO> content(@RequestParam("connectionId") String connectionId,
                                                       @RequestParam("path") String path) {
        try {
            SshFileContentResponseDTO content = sshFileCase.content(connectionId, path);
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                    .data(content).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("Exception while reading file connectionId={} path={}", connectionId, path, e);
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info("Failed to read file: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "content-chunk", method = RequestMethod.GET)
    public Response<SshFileContentResponseDTO> contentChunk(@RequestParam("connectionId") String connectionId,
                                                            @RequestParam("path") String path,
                                                            @RequestParam(value = "offset", required = false) Long offset,
                                                            @RequestParam(value = "limit", required = false) Integer limit) {
        try {
            SshFileContentResponseDTO content = sshFileCase.contentChunk(connectionId, path, offset, limit);
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                    .data(content).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("Exception while reading file chunk connectionId={} path={} offset={}", connectionId, path, offset, e);
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info("Failed to read file: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "create-file", method = RequestMethod.POST)
    public Response<Void> createFile(@RequestParam("connectionId") String connectionId,
                                     @RequestParam("path") String path,
                                     @RequestParam(value = "sudo", defaultValue = "false") boolean sudo) {
        try {
            sshFileCase.createFile(connectionId, path, sudo);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info("File created successfully").build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("Exception while creating file connectionId={} path={}", connectionId, path, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode()).info("Failed to create file: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "create-directory", method = RequestMethod.POST)
    public Response<Void> createDirectory(@RequestParam("connectionId") String connectionId,
                                          @RequestParam("path") String path,
                                          @RequestParam(value = "sudo", defaultValue = "false") boolean sudo) {
        try {
            sshFileCase.createDirectory(connectionId, path, sudo);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info("Directory created successfully").build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("Exception while creating directory connectionId={} path={}", connectionId, path, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode()).info("Failed to create directory: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "rename", method = RequestMethod.POST)
    public Response<Void> rename(@RequestParam("connectionId") String connectionId,
                                 @RequestParam("oldPath") String oldPath,
                                 @RequestParam("newPath") String newPath,
                                 @RequestParam(value = "sudo", defaultValue = "false") boolean sudo) {
        try {
            sshFileCase.rename(connectionId, oldPath, newPath, sudo);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info("Renamed successfully").build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("Exception while renaming connectionId={} oldPath={} newPath={}", connectionId, oldPath, newPath, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode()).info("Failed to rename: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "delete", method = RequestMethod.POST)
    public Response<Void> delete(@RequestParam("connectionId") String connectionId,
                                 @RequestParam("path") String path,
                                 @RequestParam(value = "sudo", defaultValue = "false") boolean sudo) {
        try {
            sshFileCase.delete(connectionId, path, sudo);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info("Deleted successfully").build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("Exception while deleting connectionId={} path={}", connectionId, path, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode()).info("Failed to delete: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "save-content", method = RequestMethod.POST)
    public Response<Void> saveContent(@RequestParam("connectionId") String connectionId,
                                      @RequestParam("path") String path,
                                      @RequestParam(value = "sudo", defaultValue = "false") boolean sudo,
                                      @RequestBody Map<String, String> body) {
        try {
            String content = body.getOrDefault("content", "");
            sshFileCase.save(connectionId, path, content, sudo);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info("File saved successfully").build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("Exception while saving file connectionId={} path={}", connectionId, path, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode()).info("Failed to save file: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "upload", method = RequestMethod.POST)
    public Response<Void> upload(@RequestParam("connectionId") String connectionId,
                                 @RequestParam("path") String path,
                                 @RequestParam("file") MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            sshFileCase.upload(connectionId, path, inputStream);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info("File uploaded successfully").build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("Exception while uploading file connectionId={} path={}", connectionId, path, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode()).info("Failed to upload file: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "download", method = RequestMethod.GET)
    public void download(@RequestParam("connectionId") String connectionId,
                         @RequestParam("path") String path,
                         HttpServletResponse response) {
        try {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(fileName, "UTF-8"));
            try (OutputStream outputStream = response.getOutputStream()) {
                sshFileCase.download(connectionId, path, outputStream);
            }
        } catch (Exception e) {
            log.error("Exception while downloading file connectionId={} path={}", connectionId, path, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

}
