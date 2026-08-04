package com.agenttrail.capability.analytics.glossary;

import com.agenttrail.loop.tools.JsonToolCallback;
import com.agenttrail.loop.tools.ToolArguments;
import org.springframework.ai.tool.ToolCallback;

/** 按术语或同义词精确查询业务口径。 */
public final class LookupGlossaryTool {
    private LookupGlossaryTool() { }

    public static ToolCallback callback(GlossaryCatalog catalog) {
        String terms = String.join("、", catalog.allTerms());
        return new JsonToolCallback(
                "lookup_glossary",
                "按业务术语或同义词精确查询口径；不做模糊匹配。\n"
                        + "当前已登记的术语：" + terms,
                "{\"type\":\"object\",\"properties\":{\"term\":{\"type\":\"string\"}},\"required\":[\"term\"]}",
                arguments -> lookup(catalog, arguments));
    }

    private static String lookup(GlossaryCatalog catalog, ToolArguments arguments) {
        String term = arguments.text("term");
        if (term == null || term.isBlank()) {
            return "Error: term 不能为空";
        }
        return catalog.format(term);
    }
}
