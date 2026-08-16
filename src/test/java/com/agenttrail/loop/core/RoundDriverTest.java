package com.agenttrail.loop.core;

import com.agenttrail.runtime.model.ModelChunk;
import com.agenttrail.runtime.model.ModelGateway;
import com.agenttrail.runtime.model.ModelRequest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RoundDriverTest {
    @Test
    void delegatesExactlyOneRoundToTheModelGateway() {
        AtomicReference<ModelRequest> received = new AtomicReference<>();
        ModelGateway gateway = request -> {
            received.set(request);
            return Flux.just(new ModelChunk("answer", null, null, "stop"));
        };

        ModelRequest request = new ModelRequest(java.util.List.of(), java.util.List.of());
        ModelChunk chunk = Flux.from(new RoundDriver(gateway).drive(request)).blockFirst();

        assertThat(chunk.content()).isEqualTo("answer");
        assertThat(received.get()).isSameAs(request);
    }
}
