package com.agenttrail.runtime;

import com.agenttrail.runtime.model.ModelChunk;
import com.agenttrail.runtime.model.ModelGateway;
import com.agenttrail.runtime.model.ModelRequest;
import org.reactivestreams.Publisher;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/** Model-round seam. The current legacy executor remains the compatibility facade. */
public final class RoundDriver {
    private final ModelGateway modelGateway;
    private final BiFunction<ModelRequest, List<?>, Publisher<ModelChunk>> roundFunction;

    public RoundDriver(ModelGateway modelGateway) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.roundFunction = (request, ignored) -> this.modelGateway.streamRound(request);
    }

    public RoundDriver(BiFunction<ModelRequest, List<?>, Publisher<ModelChunk>> roundFunction) {
        this.modelGateway = null;
        this.roundFunction = Objects.requireNonNull(roundFunction, "roundFunction");
    }

    public Publisher<ModelChunk> drive(ModelRequest request) {
        return roundFunction.apply(request, List.of());
    }

    public Publisher<ModelChunk> drive(ModelRequest request, List<?> tools) {
        return roundFunction.apply(request, tools == null ? List.of() : tools);
    }
}
