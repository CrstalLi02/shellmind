package com.shellmind.domain.coding.service.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerificationEngineTests {

    @TempDir
    Path projectRoot;

    @Test
    void planMavenProject() throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        VerificationEngine engine = new VerificationEngine(List.of(
                new MavenVerificationStrategy(),
                new GradleVerificationStrategy(),
                new NpmVerificationStrategy()
        ));

        VerificationPlan plan = engine.plan(projectRoot);
        assertEquals("maven", plan.projectType());
        assertEquals(List.of("mvn", "-q", "test"), plan.commands());
    }

    @Test
    void planUnknownProject() {
        VerificationEngine engine = new VerificationEngine(List.of(
                new MavenVerificationStrategy(),
                new GradleVerificationStrategy(),
                new NpmVerificationStrategy()
        ));

        VerificationPlan plan = engine.plan(projectRoot);
        assertEquals("unknown", plan.projectType());
    }
}
