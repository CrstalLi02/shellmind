package com.shellmind.domain.coding.service.verification;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
class NpmVerificationStrategy implements VerificationStrategy {

    @Override
    public boolean supports(Path projectRoot) {
        return Files.exists(projectRoot.resolve("package.json"));
    }

    @Override
    public VerificationPlan plan(Path projectRoot) {
        return new VerificationPlan("npm", List.of("npm", "test", "--if-present"), "Detected package.json");
    }
}
