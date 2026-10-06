package com.shellmind.cases.react.channel;

import com.shellmind.domain.agent.adapter.port.ClientChannel;

import java.util.function.Consumer;

/**
 * Channel that discards all events: used for non-streaming chat; the result is returned from ReActResultDTO.
 */
public final class DiscardingClientChannel implements ClientChannel {

    @Override
    public void send(String data) {
        // Non-streaming chat does not push intermediate events
    }

    @Override
    public void complete() {
    }

    @Override
    public void completeWithError(Throwable error) {
    }

    @Override
    public void onCompletion(Runnable callback) {
    }

    @Override
    public void onTimeout(Runnable callback) {
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
    }
}
