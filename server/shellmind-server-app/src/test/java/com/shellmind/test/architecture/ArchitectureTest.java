package com.shellmind.test.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Hexagonal-architecture constraints (run with mvn package):
 * <pre>
 *   trigger (driving adapter) ──▶ case (application service) ──▶ domain (domain + ports) ◀── infrastructure (driven adapter)
 * </pre>
 * Violating any rule fails the build. See docs/architecture.md.
 */
@AnalyzeClasses(packages = "com.shellmind", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String DOMAIN = "com.shellmind.domain..";
    private static final String CASES = "com.shellmind.cases..";
    private static final String TRIGGER = "com.shellmind.trigger..";
    private static final String INFRASTRUCTURE = "com.shellmind.infrastructure..";
    private static final String PORTS = "com.shellmind.domain..adapter.port..";

    /** The domain layer depends only on the JDK, Spring container annotations, and a few general libraries; external tech goes through ports */
    @ArchTest
    static final ArchRule domain_is_technology_free = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.google.adk..", "com.google.genai..",
                    "org.springframework.ai..", "com.openai..",
                    "io.reactivex..",
                    "org.springframework.web..", "org.springframework.http..",
                    "com.jcraft..",
                    "org.apache.ibatis..", "org.mybatis..",
                    "jakarta.servlet..")
            .because("the domain layer accesses external tech through ports (domain.*.adapter.port)");

    @ArchTest
    static final ArchRule domain_does_not_spawn_processes = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().callConstructor(ProcessBuilder.class)
            .orShould().callMethod(Runtime.class, "exec", String.class)
            .because("local processes are created by infrastructure implementations of the ProcessRunner / LocalCommandExecutor ports");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(CASES, TRIGGER, INFRASTRUCTURE);

    /** Application services orchestrate the domain and must not know about Web or agent frameworks */
    @ArchTest
    static final ArchRule cases_are_web_and_framework_free = noClasses()
            .that().resideInAPackage(CASES)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..",
                    "com.google.adk..", "com.google.genai..", "org.springframework.ai..",
                    TRIGGER, INFRASTRUCTURE);

    /** Driving adapters may enter the system only through application services; the SSE channel implementing the domain ClientChannel port is the only exception */
    @ArchTest
    static final ArchRule trigger_goes_through_cases = noClasses()
            .that().resideInAPackage(TRIGGER)
            .should().dependOnClassesThat(resideInAPackage(DOMAIN).and(not(resideInAPackage(PORTS))))
            .orShould().dependOnClassesThat().resideInAPackage(INFRASTRUCTURE);

    @ArchTest
    static final ArchRule infrastructure_does_not_depend_on_drivers = noClasses()
            .that().resideInAPackage(INFRASTRUCTURE)
            .should().dependOnClassesThat().resideInAnyPackage(CASES, TRIGGER);

    /** Bounded contexts must not depend on each other cyclically */
    @ArchTest
    static final ArchRule bounded_contexts_are_acyclic = slices()
            .matching("com.shellmind.domain.(*)..")
            .should().beFreeOfCycles();
}
