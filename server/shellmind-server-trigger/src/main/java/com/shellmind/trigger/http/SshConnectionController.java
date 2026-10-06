package com.shellmind.trigger.http;

import com.shellmind.api.ISshConnectionService;
import com.shellmind.api.dto.SshConnectionRequestDTO;
import com.shellmind.api.dto.SshConnectionResponseDTO;
import com.shellmind.api.response.Response;
import com.shellmind.cases.ssh.SshConnectionCase;
import com.shellmind.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * SSH connection management HTTP controller
 *
 * @author waissh dev
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ssh")
@CrossOrigin(origins = "*")
public class SshConnectionController implements ISshConnectionService {

    @Resource
    private SshConnectionCase sshConnectionCase;

    @RequestMapping(value = "create_connection", method = RequestMethod.POST)
    @Override
    public Response<SshConnectionResponseDTO> createConnection(@RequestBody SshConnectionRequestDTO requestDTO) {
        try {
            log.info("Create SSH connection name={} host={}", requestDTO.getConnectionName(), requestDTO.getHost());

            SshConnectionResponseDTO created = sshConnectionCase.create(requestDTO);

            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(created)
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameters while creating SSH connection: {}", e.getMessage());
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Failed to create SSH connection", e);
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "update_connection", method = RequestMethod.POST)
    @Override
    public Response<SshConnectionResponseDTO> updateConnection(@RequestBody SshConnectionRequestDTO requestDTO) {
        try {
            log.info("Update SSH connection connectionId={}", requestDTO.getConnectionId());

            // Return the full record after update
            SshConnectionResponseDTO updated = sshConnectionCase.update(requestDTO);

            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(updated)
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameters while updating SSH connection: {}", e.getMessage());
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Failed to update SSH connection connectionId={}", requestDTO.getConnectionId(), e);
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "delete_connection", method = RequestMethod.POST)
    @Override
    public Response<Void> deleteConnection(@RequestParam("connectionId") String connectionId) {
        try {
            log.info("Delete SSH connection connectionId={}", connectionId);
            sshConnectionCase.delete(connectionId);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameters while deleting SSH connection: {}", e.getMessage());
            return Response.<Void>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Failed to delete SSH connection connectionId={}", connectionId, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "get_connection", method = RequestMethod.GET)
    @Override
    public Response<SshConnectionResponseDTO> getConnection(@RequestParam("connectionId") String connectionId) {
        try {
            log.info("Query SSH connection connectionId={}", connectionId);
            SshConnectionResponseDTO connection = sshConnectionCase.get(connectionId).orElse(null);

            if (connection == null) {
                return Response.<SshConnectionResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("Connection does not exist")
                        .build();
            }

            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(connection)
                    .build();
        } catch (Exception e) {
            log.error("Failed to query SSH connection connectionId={}", connectionId, e);
            return Response.<SshConnectionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "connection_list", method = RequestMethod.GET)
    @Override
    public Response<List<SshConnectionResponseDTO>> getConnectionList(@RequestParam(value = "userId", defaultValue = "default") String userId) {
        try {
            log.info("Query SSH connection list userId={}", userId);
            // Correct status from the actual SSH connection state
            List<SshConnectionResponseDTO> dtoList = sshConnectionCase.list(userId);

            return Response.<List<SshConnectionResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dtoList)
                    .build();
        } catch (Exception e) {
            log.error("Failed to query SSH connection list userId={}", userId, e);
            return Response.<List<SshConnectionResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "connect", method = RequestMethod.POST)
    @Override
    public Response<Void> connect(@RequestParam("connectionId") String connectionId) {
        try {
            log.info("Establish SSH connection connectionId={}", connectionId);
            boolean success = sshConnectionCase.connect(connectionId);

            if (success) {
                return Response.<Void>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info("Connected successfully")
                        .build();
            } else {
                return Response.<Void>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("Connection failed. Please check the host, port, and credentials")
                        .build();
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameters while establishing SSH connection: {}", e.getMessage());
            return Response.<Void>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Failed to establish SSH connection connectionId={}", connectionId, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Connection failed: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "disconnect", method = RequestMethod.POST)
    @Override
    public Response<Void> disconnect(@RequestParam("connectionId") String connectionId) {
        try {
            log.info("Disconnect SSH connectionId={}", connectionId);
            sshConnectionCase.disconnect(connectionId);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("Disconnected")
                    .build();
        } catch (Exception e) {
            log.error("Failed to disconnect SSH connectionId={}", connectionId, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Failed to disconnect: " + e.getMessage())
                    .build();
        }
    }

}
