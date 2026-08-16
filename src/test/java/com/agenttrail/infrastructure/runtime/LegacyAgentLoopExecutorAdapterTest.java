package com.agenttrail.infrastructure.runtime;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.CancellationReason;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LegacyAgentLoopExecutorAdapterTest {

    @Test
    void cancelUsesTheTaskManagerInjectedIntoTheAdapter() {
        AgentLoopExecutor delegate = mock(AgentLoopExecutor.class);
        AgentTaskManager taskManager = mock(AgentTaskManager.class);
        LegacyAgentLoopExecutorAdapter adapter = new LegacyAgentLoopExecutorAdapter(delegate, taskManager);

        adapter.cancel(RunId.of("conversation-1"), CancellationReason.USER_REQUESTED);

        verify(taskManager).stopTask("conversation-1");
    }
}
