package com.shellmind.domain.coding.service.verification;

import java.nio.file.Path;

public interface VerificationStrategy {

    boolean supports(Path projectRoot);

    VerificationPlan plan(Path projectRoot);
}
