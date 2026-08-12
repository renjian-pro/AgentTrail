package com.agenttrail.web.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenCaseControllerSecurityTest {

    @Test
    void everyGoldenCaseMutationAndReadEndpointDeclaresItsPermission() throws Exception {
        assertPermission("list", "golden:case:view");
        assertPermission("create", "golden:case:create");
        assertPermission("update", "golden:case:update");
        assertPermission("delete", "golden:case:delete");
    }

    @Test
    void candidateEndpointsRequireTheCandidateReadPermission() throws Exception {
        Method conversations = GoldenCandidateController.class.getMethod("conversations", int.class, int.class);
        Method candidates = GoldenCandidateController.class.getMethod("candidates", String.class);

        assertThat(conversations.getAnnotation(SaCheckPermission.class).value())
                .containsExactly("golden:candidate:view");
        assertThat(candidates.getAnnotation(SaCheckPermission.class).value())
                .containsExactly("golden:candidate:view");
    }

    private static void assertPermission(String methodName, String permission) throws Exception {
        Method method = switch (methodName) {
            case "list" -> GoldenCaseController.class.getMethod(methodName);
            case "create" -> GoldenCaseController.class.getMethod(methodName, com.agenttrail.evaluation.GoldenCaseRequest.class);
            case "update" -> GoldenCaseController.class.getMethod(methodName, String.class,
                    com.agenttrail.evaluation.GoldenCaseRequest.class);
            case "delete" -> GoldenCaseController.class.getMethod(methodName, String.class);
            default -> throw new IllegalArgumentException(methodName);
        };
        assertThat(method.getAnnotation(SaCheckPermission.class)).isNotNull();
        assertThat(method.getAnnotation(SaCheckPermission.class).value()).containsExactly(permission);
    }
}
