package com.shellmind.domain.ssh.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SSH connection status
 *
 * @author waissh dev
 */
@Getter
@AllArgsConstructor
public enum ConnectionStatusEnum {

    DISCONNECTED(0, "Disconnected"),
    CONNECTED(1, "Connected"),
    CONNECTING(2, "Connecting"),
    FAILED(3, "Connection failed");

    private final int code;
    private final String desc;

    public static ConnectionStatusEnum fromCode(int code) {
        for (ConnectionStatusEnum value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return DISCONNECTED;
    }

}
