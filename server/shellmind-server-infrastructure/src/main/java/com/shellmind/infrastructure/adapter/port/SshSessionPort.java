package com.shellmind.infrastructure.adapter.port;

import com.shellmind.domain.ssh.adapter.port.ISshSessionPort;
import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH session manager for all active SSH connections.
 */
@Slf4j
@Component
public class SshSessionPort implements ISshSessionPort {

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final JSch jsch = new JSch();

    /**
     * Open an SSH connection.
     *
     * @param connectionId connection ID
     * @param host         host address
     * @param port         port
     * @param username     username
     * @param password     password (password auth)
     * @param privateKey   private key (key auth)
     * @return whether the connection succeeded
     */
    public boolean connect(String connectionId, String host, int port, String username,
                           String password, String privateKey) {
        // Disconnect first if already connected
        disconnect(connectionId);

        try {
            Session session = jsch.getSession(username, host, port);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("ServerAliveInterval", "30");   // send keep-alive every 30 seconds
            session.setConfig("ServerAliveCountMax", "3");     // drop after 3 unanswered keep-alives
            session.setTimeout(0); // no socket timeout, so the reader thread is not killed by idle

            if (privateKey != null && !privateKey.isEmpty()) {
                // Private-key auth
                jsch.addIdentity(connectionId, privateKey.getBytes(), null, null);
            } else if (password != null && !password.isEmpty()) {
                // Password auth
                session.setPassword(password);
            } else {
                log.error("SSH connect failed: no credentials provided connectionId={}", connectionId);
                return false;
            }

            session.connect();
            sessions.put(connectionId, session);
            log.info("SSH connected connectionId={} host={}:{} user={}", connectionId, host, port, username);
            return true;
        } catch (JSchException e) {
            log.error("SSH connect failed connectionId={} host={}:{} error={}", connectionId, host, port, e.getMessage());
            return false;
        }
    }

    @Lazy
    @Resource
    private SshFilePort sshFilePort;

    /**
     * Close an SSH connection.
     *
     * @param connectionId connection ID
     */
    public void disconnect(String connectionId) {
        log.info("Disconnecting SSH connectionId={}", connectionId);
        
        // Close the SFTP channel associated with this connection first
        try {
            if (sshFilePort != null) {
                sshFilePort.closeSftp(connectionId);
            }
        } catch (Exception e) {
            log.warn("Exception while closing SFTP channel: {}", e.getMessage());
        }
        
        Session session = sessions.remove(connectionId);
        if (session != null) {
            try {
                if (session.isConnected()) {
                    session.disconnect();
                    log.info("SSH disconnected connectionId={}", connectionId);
                }
            } catch (Exception e) {
                log.warn("Exception while disconnecting SSH: {}", e.getMessage());
            }
        }
    }

    /**
     * Check whether the connection is alive.
     *
     * @param connectionId connection ID
     * @return whether it is connected
     */
    public boolean isConnected(String connectionId) {
        Session session = sessions.get(connectionId);
        if (session == null) {
            return false;
        }
        try {
            // Beyond isConnected, do a lightweight live check
            if (session.isConnected()) {
                // Send a simple ignore packet to verify the connection is usable
                session.sendIgnore();
                return true;
            }
        } catch (Exception e) {
            // An exception means the connection is already gone
            log.warn("Connection status check failed; connection may already be down: {}", e.getMessage());
            // Clean up the stale connection
            try {
                disconnect(connectionId);
            } catch (Exception ex) {
                log.warn("Exception while cleaning up a stale connection: {}", ex.getMessage());
            }
            return false;
        }
        return false;
    }

    /**
     * Get the session.
     *
     * @param connectionId connection ID
     * @return JSch Session
     */
    public Session getSession(String connectionId) {
        return sessions.get(connectionId);
    }
}
