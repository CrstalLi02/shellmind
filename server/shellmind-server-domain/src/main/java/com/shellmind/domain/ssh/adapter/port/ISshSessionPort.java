package com.shellmind.domain.ssh.adapter.port;

/**
 * SSH session port.
 * Used to establish and tear down SSH connections.
 */
public interface ISshSessionPort {

    /**
     * Establish an SSH connection.
     *
     * @param connectionId connection ID
     * @param host         host address
     * @param port         port
     * @param username     username
     * @param password     password
     * @param privateKey   private key
     * @return whether the connection succeeded
     */
    boolean connect(String connectionId, String host, int port, String username,
                    String password, String privateKey);

    /**
     * Disconnect an SSH connection.
     *
     * @param connectionId connection ID
     */
    void disconnect(String connectionId);

    /**
     * Check whether the connection is established.
     *
     * @param connectionId connection ID
     * @return whether it is connected
     */
    boolean isConnected(String connectionId);
}
