package com.agenttrail.runtime;

import com.agenttrail.loop.pause.InMemoryPauseStateStore;
import com.agenttrail.loop.pause.PauseConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeProfileValidatorTest {
    @Test
    void rejectsEnabledPauseWithoutStore() {
        RuntimeProfile invalid = new RuntimeProfile(
                RuntimeModule.contextCompaction(com.agenttrail.loop.context.ContextPolicy.defaults()),
                RuntimeModule.memory(null),
                RuntimeModule.pauseResume(new PauseConfig(Set.of("shell"), null)),
                RuntimeModule.trace(null),
                RuntimeModule.stageOutput(com.agenttrail.loop.stageoutput.StageOutputManager.EMPTY),
                RuntimeModule.toolSearch(null));

        assertThrows(IllegalStateException.class, () -> RuntimeProfileValidator.validate(invalid));
    }

    @Test
    void acceptsExplicitlyDisabledPauseAndValidEnabledPause() {
        assertDoesNotThrow(() -> RuntimeProfileValidator.validate(RuntimeProfile.defaults()));
        RuntimeProfile valid = new RuntimeProfile(
                RuntimeModule.contextCompaction(com.agenttrail.loop.context.ContextPolicy.defaults()),
                RuntimeModule.memory(null),
                RuntimeModule.pauseResume(new PauseConfig(Set.of("shell"), new InMemoryPauseStateStore())),
                RuntimeModule.trace(null),
                RuntimeModule.stageOutput(com.agenttrail.loop.stageoutput.StageOutputManager.EMPTY),
                RuntimeModule.toolSearch(null));
        assertDoesNotThrow(() -> RuntimeProfileValidator.validate(valid));
    }
}
