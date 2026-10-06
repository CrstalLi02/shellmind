package com.shellmind.domain.ssh.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SSH authentication type
 *
 * @author waissh dev
 */
@Getter
@AllArgsConstructor
public enum AuthTypeEnum {

    PASSWORD(1, "Password authentication"),
    PRIVATE_KEY(2, "Private-key authentication");

    private final int code;
    private final String desc;

    public static AuthTypeEnum fromCode(int code) {
        for (AuthTypeEnum value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return PASSWORD;
    }

}
