package com.agenttrail.runtime;

import com.agenttrail.loop.context.ContextPolicy;
import com.agenttrail.loop.memory.MemoryStore;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.stageoutput.StageOutputManager;
import com.agenttrail.loop.trace.TraceStore;
import com.agenttrail.loop.tools.search.ToolCatalog;

/** Explicit runtime capabilities used by the runtime profile instead of nullable constructor slots. */
public final class RuntimeModule {
    private RuntimeModule() {
    }

    public record ContextCompaction(ContextPolicy policy) {
    }

    public record Memory(MemoryStore store) {
    }

    public record PauseResume(PauseConfig config) {
        public static final PauseResume DISABLED = new PauseResume(null);

        public boolean enabled() {
            return config != null;
        }
    }

    public record Trace(TraceStore store) {
    }

    public record StageOutput(StageOutputManager manager) {
    }

    public record ToolSearch(ToolCatalog catalog) {
    }

    public static ContextCompaction contextCompaction(ContextPolicy policy) {
        return new ContextCompaction(policy);
    }

    public static Memory memory(MemoryStore store) {
        return new Memory(store);
    }

    public static PauseResume pauseResume(PauseConfig config) {
        return config == null ? PauseResume.DISABLED : new PauseResume(config);
    }

    public static Trace trace(TraceStore store) {
        return new Trace(store);
    }

    public static StageOutput stageOutput(StageOutputManager manager) {
        return new StageOutput(manager);
    }

    public static ToolSearch toolSearch(ToolCatalog catalog) {
        return new ToolSearch(catalog);
    }
}
