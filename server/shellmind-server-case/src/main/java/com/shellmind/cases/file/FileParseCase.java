package com.shellmind.cases.file;

import com.shellmind.domain.ssh.service.file.ClassFileParser;
import org.springframework.stereotype.Service;

/**
 * Local file-parse use case (desktop preview).
 */
@Service
public class FileParseCase {

    public boolean isClassFile(byte[] bytes) {
        return ClassFileParser.isClassFile(bytes);
    }

    public String parseClass(byte[] bytes, String name) {
        return ClassFileParser.parse(bytes, name);
    }
}
