package com.agenttrail.runtime;

import java.util.Objects;
import java.util.function.Consumer;

public final class RunCompletionCoordinator<T> {
    private final Consumer<T> completion;

    public RunCompletionCoordinator(Consumer<T> completion) {
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    public void complete(T result) {
        completion.accept(result);
    }
}
