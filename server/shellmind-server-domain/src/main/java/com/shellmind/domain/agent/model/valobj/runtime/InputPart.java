package com.shellmind.domain.agent.model.valobj.runtime;

/**
 * User input fragment: text, inline binary (e.g. an image), or a file URI — one of the three.
 */
public record InputPart(String text, byte[] data, String uri, String mimeType) {

    public static InputPart text(String text) {
        return new InputPart(text, null, null, null);
    }

    public static InputPart inline(byte[] data, String mimeType) {
        return new InputPart(null, data, null, mimeType);
    }

    public static InputPart uri(String uri, String mimeType) {
        return new InputPart(null, null, uri, mimeType);
    }

    public boolean isText() {
        return text != null;
    }
}
