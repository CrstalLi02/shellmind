package com.shellmind.domain.llm.adapter.port;

/**
 * Probe model-endpoint connectivity: send one minimal request to the completions API.
 */
public interface ModelEndpointProbe {

    /**
     * @param completionEndpoint full completions endpoint URL
     * @param apiKey             API key
     * @param modelName          model ID
     * @return raw API response (may be empty)
     * @throws RuntimeException on connection failure, auth failure, or API error
     */
    String probe(String completionEndpoint, String apiKey, String modelName);
}
