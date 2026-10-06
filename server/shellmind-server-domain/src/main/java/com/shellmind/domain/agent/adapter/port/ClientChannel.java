package com.shellmind.domain.agent.adapter.port;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Unidirectional event channel to the client (server → client).
 * <p>
 * One streaming conversation maps to one channel: the use-case layer pushes round/text/tool events
 * through it, and local command dispatch plus tool-progress notifications also go out on it.
 * Transport (SSE / WebSocket) is implemented by the interface layer; domain and use-case layers
 * are unaware of any web technology.
 */
public interface ClientChannel {

    /**
     * Send one event payload (the caller is responsible for serialization and newlines).
     *
     * @throws IOException the client has disconnected or the write failed
     */
    void send(String data) throws IOException;

    /** End the channel normally */
    void complete();

    /** End the channel with an error */
    void completeWithError(Throwable error);

    /** Callback when the channel ends normally (including client-initiated close) */
    void onCompletion(Runnable callback);

    /** Callback when the channel times out */
    void onTimeout(Runnable callback);

    /** Callback when the channel errors */
    void onError(Consumer<Throwable> callback);
}
