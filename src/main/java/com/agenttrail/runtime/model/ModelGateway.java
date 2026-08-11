package com.agenttrail.runtime.model;

import org.reactivestreams.Publisher;

public interface ModelGateway {
    Publisher<ModelChunk> streamRound(ModelRequest request);
}
