package com.shellmind.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@Getter
public enum ResponseCode {

    SUCCESS("0000", "Success"),
    UN_ERROR("0001", "Unknown error"),
    ILLEGAL_PARAMETER("0002", "Illegal parameter"),
    NOT_FOUND_METHOD("0003", "Method not found"),

    E0001("E0001", "Agent ID does not exist"),
    E0002("E0002", "Agent MCP config is not in the loadable range"),

    ;

    private String code;
    private String info;

}
