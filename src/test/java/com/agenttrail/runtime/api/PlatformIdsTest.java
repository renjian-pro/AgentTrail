package com.agenttrail.runtime.api;

import com.agenttrail.platform.ids.ArtifactId;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.platform.ids.TaskId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformIdsTest {
    @Test
    void generatesDistinctStronglyTypedIds() {
        assertNotEquals(RunId.newId(), RunId.newId());
        assertNotEquals(TaskId.newId(), TaskId.newId());
        assertNotEquals(ConversationId.newId(), ConversationId.newId());
        assertNotEquals(ArtifactId.newId(), ArtifactId.newId());
    }

    @Test
    void rejectsNullIds() {
        assertThrows(NullPointerException.class, () -> RunId.of(null));
        assertThrows(NullPointerException.class, () -> TaskId.of(null));
        assertThrows(NullPointerException.class, () -> ConversationId.of(null));
        assertThrows(NullPointerException.class, () -> ArtifactId.of(null));
    }
}
