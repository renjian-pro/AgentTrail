package com.agenttrail.capability.analytics.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalculateToolTest {
    @Test
    void calculatesAndRoundsWithVariables() {
        String result = CalculateTool.callback().call(
                "{\"expression\":\"round((a-b)/b*100,2)\",\"variablesJson\":\"{\\\"a\\\":3298,\\\"b\\\":3105}\"}");
        assertThat(result).isEqualTo("6.22");
    }

    @Test
    void rejectsNonMathInput() {
        String result = CalculateTool.callback().call(
                "{\"expression\":\"read_file('/etc/passwd')\",\"variablesJson\":\"{}\"}");
        assertThat(result).startsWith("Error:");
    }
}
