package com.agenttrail.loop.skills;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营接口：{@link SkillManager} 本身的读写能力早就有了，缺的是把它暴露成 HTTP 端点这一层。
 *
 * <p>照搬 {@code CapabilityControllersTest} 的写法——mock 依赖、直接 new 出 controller 调方法，
 * 不经 MockMvc/Sa-Token：权限校验是方法级 AOP 注解，脱离 Spring 容器直接调用不会触发，
 * 这层测试只验证 controller 把请求正确转译成了对 {@link SkillManager} 的调用，权限码是否
 * 生效由 {@code @SaCheckPermission} 本身的现成机制保证，不用重复测。
 */
class SkillControllerTest {

    @Test
    void listReturnsWhateverSkillManagerReturns() {
        SkillManager skillManager = mock(SkillManager.class);
        List<SkillMetadata> skills = List.of(new SkillMetadata("data-analysis", "/skills/data-analysis", "数据分析", true));
        when(skillManager.list()).thenReturn(skills);
        SkillController controller = new SkillController(skillManager);

        assertThat(controller.list()).isEqualTo(skills);
    }

    @Test
    void updateEnabledDelegatesToSkillManager() {
        SkillManager skillManager = mock(SkillManager.class);
        SkillController controller = new SkillController(skillManager);

        controller.updateEnabled("data-analysis", new SkillEnabledRequest(false));

        verify(skillManager).setEnabled("data-analysis", false);
    }

    @Test
    void updateEnabledTranslatesUnknownSkillIntoBadRequestInsteadOfLeakingA500() {
        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.setEnabled("ghost", true)).thenThrow(new IllegalArgumentException("技能不存在: ghost"));
        SkillController controller = new SkillController(skillManager);

        assertThatThrownBy(() -> controller.updateEnabled("ghost", new SkillEnabledRequest(true)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(((ResponseStatusException) failure).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("技能不存在: ghost");
    }
}
