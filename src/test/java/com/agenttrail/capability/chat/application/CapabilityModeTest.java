package com.agenttrail.capability.chat.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * issue #106 / R13a。这组用例守的是**一条静默失败链路**，不是解析器的边界情况：
 * 前端传错一个字符 → 后端按普通聊天跑完 → 模型没有数据库工具 → 编数据画图 → 接口 200、日志干净。
 */
class CapabilityModeTest {

    @Test
    @DisplayName("不传 mode 是合法的，落普通对话——前端没选模式时就是这条路径")
    void treatsMissingModeAsPlainChat() {
        assertThat(CapabilityMode.require(null)).isEqualTo(CapabilityMode.CHAT);
        assertThat(CapabilityMode.require("")).isEqualTo(CapabilityMode.CHAT);
        assertThat(CapabilityMode.require("   ")).isEqualTo(CapabilityMode.CHAT);
    }

    @Test
    @DisplayName("chat / analytics 正常解析——analytics 的行为必须和改造前一致")
    void parsesTheModesTheChatEndpointServes() {
        assertThat(CapabilityMode.require("chat")).isEqualTo(CapabilityMode.CHAT);
        assertThat(CapabilityMode.require("analytics")).isEqualTo(CapabilityMode.ANALYTICS);
    }

    /**
     * <b>这条是本次修复的核心。</b>改造前 {@code "analytics".equals(mode)} 会让下面每一个取值都
     * 静默变成普通聊天。四个取值覆盖四种真实的传错方式：拼写、大小写、多余空格、彻底的野值。
     */
    @Test
    @DisplayName("未注册的取值一律抛异常，绝不静默降级成普通聊天")
    void rejectsUnknownValuesInsteadOfSilentlyFallingBackToChat() {
        for (String wrong : new String[] {"Analytics", "ANALYTICS", "analytic", " analytics", "sql", "true"}) {
            assertThatThrownBy(() -> CapabilityMode.require(wrong))
                    .as("传错的 mode 值 %s 必须炸出来而不是当普通聊天跑", wrong)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(wrong);
        }
    }

    /**
     * research/ppt 是合法的模式，但**不由这个端点承载**——它们各自有创建任务 + 轮询的链路。
     * 静默当普通聊天跑的后果是用户拿到一段闲聊回复而不是一份报告，且没有任何地方能看出原因。
     */
    @Test
    @DisplayName("research / ppt 在 chat 端点上被拒绝，并指向各自的任务接口")
    void rejectsTaskModesOnTheChatEndpointAndPointsAtTheRightOne() {
        assertThat(CapabilityMode.parse("research")).isEqualTo(CapabilityMode.RESEARCH);
        assertThat(CapabilityMode.parse("ppt")).isEqualTo(CapabilityMode.PPT);

        assertThatThrownBy(() -> CapabilityMode.require("research"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/agent/v1/deepresearch");
        assertThatThrownBy(() -> CapabilityMode.require("ppt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/agent/v1/ppt");
    }

    /**
     * {@code parse} 用 null 表示"传了但不认识"，{@code CHAT} 表示"没传"——两者必须能区分开，
     * 否则 400 判定无从下手。这条钉住的是那个区分本身，而不是某个具体取值。
     */
    @Test
    @DisplayName("parse 把「没传」和「传错」区分开：前者是 CHAT，后者是 null")
    void distinguishesAbsentFromUnrecognised() {
        assertThat(CapabilityMode.parse(null)).isEqualTo(CapabilityMode.CHAT);
        assertThat(CapabilityMode.parse("nonsense")).isNull();
    }

    @Test
    @DisplayName("servedByChatEndpoint 划的是协议边界：SSE 单次流 vs 异步任务")
    void marksWhichModesTheChatProtocolCanCarry() {
        assertThat(CapabilityMode.CHAT.servedByChatEndpoint()).isTrue();
        assertThat(CapabilityMode.ANALYTICS.servedByChatEndpoint()).isTrue();
        assertThat(CapabilityMode.RESEARCH.servedByChatEndpoint()).isFalse();
        assertThat(CapabilityMode.PPT.servedByChatEndpoint()).isFalse();
    }
}
