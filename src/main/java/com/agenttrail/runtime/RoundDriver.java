package com.agenttrail.runtime;

import com.agenttrail.runtime.model.ModelChunk;
import com.agenttrail.runtime.model.ModelGateway;
import com.agenttrail.runtime.model.ModelRequest;
import org.reactivestreams.Publisher;

/** Model-round seam. The current legacy executor remains the compatibility facade. */
public final class RoundDriver {
    private final ModelGateway modelGateway;

    public RoundDriver(ModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    public Publisher<ModelChunk> drive(ModelRequest request) {
        return modelGateway.streamRound(request);
    }
}
