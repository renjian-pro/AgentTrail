package com.agenttrail.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureBaselineTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.agenttrail");

    @Test
    @Disabled("Current package layout intentionally violates this target; reopen after Phase 5-9 migration.")
    void capabilityShouldNotDependOnWebDto() {
        ArchRule rule = noClasses().that().resideInAnyPackage("com.agenttrail.capability..")
                .should().dependOnClassesThat().resideInAnyPackage("com.agenttrail.web.dto..");
        rule.check(classes);
    }

    @Test
    @Disabled("Current package layout intentionally violates this target; reopen after Phase 3 migration.")
    void loopCoreShouldNotDependOnCapability() {
        ArchRule rule = noClasses().that().resideInAnyPackage("com.agenttrail.loop.core..")
                .should().dependOnClassesThat().resideInAnyPackage("com.agenttrail.capability..");
        rule.check(classes);
    }

    @Test
    @Disabled("Current capability APIs still expose Spring AI types; reopen after Ticket 13.")
    void capabilityShouldNotDependOnSpringAi() {
        ArchRule rule = noClasses().that().resideInAnyPackage("com.agenttrail.capability..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..", "reactor..");
        rule.check(classes);
    }

    @Test
    void controllersShouldNotDependOnJdbcDirectly() {
        ArchRule rule = noClasses().that().resideInAnyPackage("com.agenttrail.web.controller..")
                .should().dependOnClassesThat().resideInAnyPackage("javax.sql..", "java.sql..");
        rule.check(classes);
    }
}
