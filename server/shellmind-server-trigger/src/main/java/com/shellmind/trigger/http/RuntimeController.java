package com.shellmind.trigger.http;

import com.shellmind.api.response.Response;
import com.shellmind.types.enums.ResponseCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/runtime")
@Profile("local")
public class RuntimeController {

    @Value("${spring.profiles.active:local}")
    private String profile;

    @Value("${shellmind.local.workspace:${user.home}}")
    private String workspace;

    @GetMapping("/health")
    public Response<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "ready");
        data.put("profile", profile);
        data.put("workspace", workspace);
        data.put("pid", ProcessHandle.current().pid());
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }
}
