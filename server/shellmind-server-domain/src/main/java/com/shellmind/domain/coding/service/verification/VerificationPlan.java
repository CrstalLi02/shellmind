package com.shellmind.domain.coding.service.verification;

import java.util.List;

public record VerificationPlan(String projectType, List<String> commands, String reason) {
}
