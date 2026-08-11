package com.agenttrail.loop.core;

import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.structured.JsonRepair;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared executor-call, JSON repair, deserialization, and parse-failure wrapping. */
public final class StructuredLlmCall {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructuredLlmCall() {
    }

    public static <T> T call(AgentLoopExecutor executor, String prompt, RunnableParams params, Class<T> type) {
        String rawJson = executor.call(prompt, params);
        return parse(rawJson, type);
    }

    public static <T> T parse(String rawJson, Class<T> type) {
        String fixedJson = JsonRepair.fixJson(rawJson);
        try {
            return MAPPER.readValue(fixedJson, type);
        } catch (Exception malformed) {
            throw new StructuredLlmCallException(rawJson, fixedJson, malformed);
        }
    }

    public static final class StructuredLlmCallException extends RuntimeException {
        private final String rawJson;
        private final String fixedJson;

        StructuredLlmCallException(String rawJson, String fixedJson, Throwable cause) {
            super("Structured LLM call failed to parse JSON: " + rawJson, cause);
            this.rawJson = rawJson;
            this.fixedJson = fixedJson;
        }

        public String rawJson() {
            return rawJson;
        }

        public String fixedJson() {
            return fixedJson;
        }
    }
}
