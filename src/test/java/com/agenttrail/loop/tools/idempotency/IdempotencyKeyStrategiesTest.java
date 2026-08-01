package com.agenttrail.loop.tools.idempotency;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyKeyStrategiesTest {

    @Test
    void digestIgnoresObjectFieldOrderRecursively() {
        IdempotencyKeyStrategy strategy = IdempotencyKeyStrategies.argumentDigest();

        Optional<String> first = strategy.deriveKey(
                "{\"b\":2,\"nested\":{\"z\":9,\"a\":1}}", null);
        Optional<String> reordered = strategy.deriveKey(
                "{\"nested\":{\"a\":1,\"z\":9},\"b\":2}", null);

        assertThat(first).isEqualTo(reordered);
        assertThat(first).hasValueSatisfying(value -> assertThat(value).hasSize(64));
    }

    @Test
    void digestPreservesArrayOrderBecauseItCanCarryBusinessMeaning() {
        IdempotencyKeyStrategy strategy = IdempotencyKeyStrategies.argumentDigest();

        Optional<String> first = strategy.deriveKey("{\"steps\":[{\"b\":2,\"a\":1},2]}", null);
        Optional<String> reorderedFields = strategy.deriveKey("{\"steps\":[{\"a\":1,\"b\":2},2]}", null);
        Optional<String> reorderedArray = strategy.deriveKey("{\"steps\":[2,{\"a\":1,\"b\":2}]}", null);

        assertThat(first).isEqualTo(reorderedFields);
        assertThat(first).isNotEqualTo(reorderedArray);
    }

    @Test
    void malformedAndBlankInputsStillProduceDeterministicDigests() {
        IdempotencyKeyStrategy strategy = IdempotencyKeyStrategies.argumentDigest();

        assertThat(strategy.deriveKey("  not-json  ", null))
                .isEqualTo(strategy.deriveKey("not-json", null));
        assertThat(strategy.deriveKey(null, null))
                .isEqualTo(strategy.deriveKey("   ", null));
    }

    @Test
    void explicitFieldOnlyReturnsNonBlankPresentValues() {
        IdempotencyKeyStrategy strategy = IdempotencyKeyStrategies.argumentField("requestId");

        assertThat(strategy.deriveKey("{\"requestId\":\"req-1\"}", null)).contains("req-1");
        assertThat(strategy.deriveKey("{\"requestId\":42}", null)).contains("42");
        assertThat(strategy.deriveKey("{\"requestId\":\"  \"}", null)).isEmpty();
        assertThat(strategy.deriveKey("{\"requestId\":null}", null)).isEmpty();
        assertThat(strategy.deriveKey("{}", null)).isEmpty();
        assertThat(strategy.deriveKey(null, null)).isEmpty();
        assertThat(strategy.deriveKey("not-json", null)).isEmpty();
    }

    @Test
    void fieldOrDigestPrefersTheExplicitTokenAndFallsBackWhenItIsMissing() {
        IdempotencyKeyStrategy strategy = IdempotencyKeyStrategies.argumentFieldOrDigest("requestId");

        assertThat(strategy.deriveKey("{\"requestId\":\"req-1\",\"amount\":10}", null))
                .contains("req-1");
        assertThat(strategy.deriveKey("{\"amount\":10}", null))
                .isEqualTo(IdempotencyKeyStrategies.argumentDigest().deriveKey("{\"amount\":10}", null));
    }
}
