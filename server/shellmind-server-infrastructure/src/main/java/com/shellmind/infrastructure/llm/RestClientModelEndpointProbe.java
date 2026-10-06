package com.shellmind.infrastructure.llm;

import com.shellmind.domain.llm.adapter.port.ModelEndpointProbe;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Connectivity probe using the OpenAI Chat Completions protocol: a non-streaming request with max_tokens=1.
 */
@Component
public class RestClientModelEndpointProbe implements ModelEndpointProbe {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    @Override
    public String probe(String completionEndpoint, String apiKey, String modelName) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("messages", List.of(Map.of("role", "user", "content", "ping")));
        body.put("max_tokens", 1);
        body.put("stream", false);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build()
                .post()
                .uri(completionEndpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);
    }
}
