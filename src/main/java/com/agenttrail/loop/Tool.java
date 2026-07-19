package com.agenttrail.loop;

import java.util.Map;

public interface Tool {

    ToolSpec spec();

    String execute(Map<String, Object> arguments);
}
