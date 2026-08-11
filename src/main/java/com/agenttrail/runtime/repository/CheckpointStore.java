package com.agenttrail.runtime.repository;

import com.agenttrail.platform.events.Checkpoint;
import com.agenttrail.platform.ids.TaskId;

import java.util.Optional;

public interface CheckpointStore {
    Checkpoint save(TaskId taskId, String stage, String stateSnapshot);

    Optional<Checkpoint> latest(TaskId taskId);

    Optional<Checkpoint> find(TaskId taskId, long version);
}
