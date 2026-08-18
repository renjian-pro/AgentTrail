package com.agenttrail.loop.tools.idempotency;

import java.util.Objects;

/** 原子占位的结果；成功方必须携带 ownerToken 才能完成或释放自己的租约。 */
public record IdempotencyClaim(String ownerToken, IdempotencyRecord existing) {

    public IdempotencyClaim {
        if ((ownerToken == null) == (existing == null)) {
            throw new IllegalArgumentException("占位结果必须且只能包含 ownerToken 或 existing 之一");
        }
    }

    public static IdempotencyClaim acquired(String ownerToken) {
        return new IdempotencyClaim(Objects.requireNonNull(ownerToken, "ownerToken"), null);
    }

    public static IdempotencyClaim occupied(IdempotencyRecord existing) {
        return new IdempotencyClaim(null, Objects.requireNonNull(existing, "existing"));
    }

    public boolean acquired() {
        return ownerToken != null;
    }
}
