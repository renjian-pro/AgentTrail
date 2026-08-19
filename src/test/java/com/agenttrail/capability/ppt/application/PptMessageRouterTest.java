package com.agenttrail.capability.ppt.application;

import com.agenttrail.capability.ppt.PptRunStatus;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptTask;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PptMessageRouterTest {

    private final PptMessageRouter router = new PptMessageRouter();

    @Test
    void createsWhenTheConversationHasNoPptTask() {
        assertThat(router.route(Optional.empty(), "生成一份 AI Agent 技术原理 PPT").action())
                .isEqualTo(PptMessageRouter.Action.CREATE);
    }

    @Test
    void treatsAnOrdinaryMessageAsTheAnswerWhileTheTaskIsWaitingForInput() {
        assertThat(router.route(Optional.of(task(PptState.AWAITING_INPUT, PptRunStatus.WAITING_INPUT)),
                "AI Agent 技术原理").action()).isEqualTo(PptMessageRouter.Action.ANSWER);
    }

    @Test
    void explicitCancellationWinsOverAnsweringAClarification() {
        assertThat(router.route(Optional.of(task(PptState.AWAITING_INPUT, PptRunStatus.WAITING_INPUT)),
                "取消这次生成").action()).isEqualTo(PptMessageRouter.Action.CANCEL);
    }

    @Test
    void routesModificationAgainstTheLatestSuccessfulTask() {
        assertThat(router.route(Optional.of(task(PptState.SUCCESS, PptRunStatus.SUCCEEDED)),
                "把第二页改成流程图").action()).isEqualTo(PptMessageRouter.Action.MODIFY);
    }

    @Test
    void routesModificationAgainstAnActiveTaskInsteadOfCreatingAnUnrelatedDeck() {
        assertThat(router.route(Optional.of(task(PptState.IMAGE, PptRunStatus.RUNNING)),
                "把第二页改成流程图").action()).isEqualTo(PptMessageRouter.Action.MODIFY);
    }

    @Test
    void routesResumeOnlyWhenThereIsAnUnfinishedTask() {
        assertThat(router.route(Optional.of(task(PptState.SCHEMA, PptRunStatus.FAILED)),
                "继续生成").action()).isEqualTo(PptMessageRouter.Action.RESUME);
    }

    @Test
    void doesNotMistakeBusinessTextContainingCancelForACancelCommand() {
        assertThat(router.route(Optional.of(task(PptState.SUCCESS, PptRunStatus.SUCCEEDED)),
                "生成一份订单取消率分析").action()).isEqualTo(PptMessageRouter.Action.CREATE);
    }

    private static PptTask task(PptState state, PptRunStatus runStatus) {
        return new PptTask(9L, "u-1", "c-1", state, runStatus, null, "{}", 1,
                3, null, null, 1, 0, 1, 2);
    }
}
