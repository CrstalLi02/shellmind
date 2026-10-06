package com.shellmind.trigger.http.sse;

import com.shellmind.domain.agent.adapter.port.ClientChannel;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * HTTP stream (application/x-ndjson) implementation of {@link ClientChannel}.
 * <p>
 * Each event is one JSON line. Proxy buffering is disabled so events reach the client in real time.
 */
public class SseClientChannel implements ClientChannel {

    /** A single conversation may last a long time when there are many tool rounds and long reports */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final ResponseBodyEmitter emitter;

    private SseClientChannel(ResponseBodyEmitter emitter) {
        this.emitter = emitter;
    }

    public static SseClientChannel open() {
        return new SseClientChannel(new ResponseBodyEmitter(TIMEOUT_MS) {
            @Override
            protected void extendResponse(ServerHttpResponse outputMessage) {
                outputMessage.getHeaders().set("Content-Type", "application/x-ndjson");
                outputMessage.getHeaders().set("X-Accel-Buffering", "no");
                outputMessage.getHeaders().set("Cache-Control", "no-cache");
            }
        });
    }

    /** Returned by the controller as the response body */
    public ResponseBodyEmitter emitter() {
        return emitter;
    }

    @Override
    public void send(String data) throws IOException {
        emitter.send(data, MediaType.APPLICATION_JSON);
    }

    @Override
    public void complete() {
        emitter.complete();
    }

    @Override
    public void completeWithError(Throwable error) {
        emitter.completeWithError(error);
    }

    @Override
    public void onCompletion(Runnable callback) {
        emitter.onCompletion(callback);
    }

    @Override
    public void onTimeout(Runnable callback) {
        emitter.onTimeout(callback);
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
        emitter.onError(callback);
    }
}
