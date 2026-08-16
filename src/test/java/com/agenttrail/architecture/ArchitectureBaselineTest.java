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

    /**
     * Phase -1 打破了 {@code loop} ↔ {@code runtime} 的包循环，这条规则负责让它不再长回来。
     *
     * <p>方向是单向的：{@code runtime} 是端口层（{@code AgentRuntimePort}/{@code ModelGateway}/
     * {@code ToolDefinition} 这些契约），{@code loop} 是实现它的手写 ReAct 运行时，所以
     * {@code loop → runtime} 允许，反向禁止。以前反过来也成立——{@code RuntimeModule} 里装着
     * {@code loop.context.ContextPolicy}、{@code RunLifecycleManager} 直接持有
     * {@code loop.task.AgentTaskManager}——那种双向依赖等于没有边界，抽象层退化成别名层。
     *
     * <p>这条规则**没有** {@code @Disabled}：它是当前真实成立的约束，不是目标态。
     */
    @Test
    void runtimePortLayerShouldNotDependOnTheLoopImplementation() {
        ArchRule rule = noClasses().that().resideInAnyPackage("com.agenttrail.runtime..")
                .should().dependOnClassesThat().resideInAnyPackage("com.agenttrail.loop..");
        rule.check(classes);
    }

    @Test
    void controllersShouldNotDependOnJdbcDirectly() {
        ArchRule rule = noClasses().that().resideInAnyPackage("com.agenttrail.web.controller..")
                .should().dependOnClassesThat().resideInAnyPackage("javax.sql..", "java.sql..");
        rule.check(classes);
    }
}
