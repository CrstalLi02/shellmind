package com.shellmind.domain.agent.service.run;

import com.shellmind.domain.agent.adapter.port.ClientChannel;
import com.shellmind.domain.shared.model.RunContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunRegistryTest {

    /** Channel stand-in that records received events */
    static class RecordingChannel implements ClientChannel {
        final List<String> sent = new ArrayList<>();

        @Override public void send(String data) { sent.add(data); }
        @Override public void complete() { }
        @Override public void completeWithError(Throwable error) { }
        @Override public void onCompletion(Runnable callback) { }
        @Override public void onTimeout(Runnable callback) { }
        @Override public void onError(Consumer<Throwable> callback) { }
    }

    private static RunContext ctx(String sessionId, String terminal) {
        return new RunContext(sessionId, "u-" + sessionId, "agent", null, false, null, terminal, false, null);
    }

    @Test
    void concurrentRunsAreIsolatedBySession() {
        AgentRunRegistry registry = new AgentRunRegistry();
        RecordingChannel channelA = new RecordingChannel();
        RecordingChannel channelB = new RecordingChannel();
        registry.register(ctx("A", "terminal-A"), channelA);
        registry.register(ctx("B", null), channelB);

        assertSame(channelA, registry.channel("A").orElseThrow());
        assertSame(channelB, registry.channel("B").orElseThrow());
        assertEquals("terminal-A", registry.requireContext("A").terminalSessionId());
        assertFalse(registry.requireContext("B").hasTerminal(), "Session B must not receive session A's terminal");
    }

    @Test
    void childSessionInheritsParentChannel() {
        AgentRunRegistry registry = new AgentRunRegistry();
        RecordingChannel parentChannel = new RecordingChannel();
        RunContext parent = ctx("P", "terminal-1");
        registry.register(parent, parentChannel);

        registry.registerChild(parent.forChildSession("P_sub_1").withReadOnly(true));

        assertSame(parentChannel, registry.channel("P_sub_1").orElseThrow());
        RunContext child = registry.requireContext("P_sub_1");
        assertEquals("P", child.parentSessionId());
        assertEquals("terminal-1", child.terminalSessionId());
        assertTrue(child.readOnly());
        assertFalse(registry.requireContext("P").readOnly(), "A derived child session must not affect the parent session");
    }

    @Test
    void unregisteredSessionHasNoContext() {
        AgentRunRegistry registry = new AgentRunRegistry();
        registry.register(ctx("A", null), new RecordingChannel());
        registry.unregister("A");

        assertTrue(registry.context("A").isEmpty());
        assertTrue(registry.channel("A").isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.requireContext("A"));
        assertTrue(registry.context(null).isEmpty());
    }

    @Test
    void progressNotificationsGoOnlyToOwningSession() {
        AgentRunRegistry registry = new AgentRunRegistry();
        RecordingChannel channelA = new RecordingChannel();
        RecordingChannel channelB = new RecordingChannel();
        registry.register(ctx("A", null), channelA);
        registry.register(ctx("B", null), channelB);
        ClientToolProgressNotifier notifier = new ClientToolProgressNotifier(registry);

        notifier.onToolStart("A", "readLocalFile", "/a.txt");
        notifier.onToolEnd("B", "writeLocalFile", "ok", true);
        notifier.onToolStart("unknown", "readLocalFile", "/x");

        assertEquals(1, channelA.sent.size());
        assertTrue(channelA.sent.get(0).contains("readLocalFile"));
        assertEquals(1, channelB.sent.size());
        assertTrue(channelB.sent.get(0).contains("writeLocalFile"));
    }
}
