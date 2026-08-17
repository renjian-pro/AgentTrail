package com.agenttrail.loop.pause;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolArgumentSanitizerTest {

    @Test
    void recursivelyRedactsSensitiveKeysWithoutChangingSafeArguments() {
        String sanitized = ToolArgumentSanitizer.sanitize(
                "{\"path\":\"a.txt\",\"api_token\":\"secret-value\","
                        + "\"nested\":{\"password\":\"p\"},\"items\":[{\"accessKey\":\"k\"}]}");

        assertThat(sanitized)
                .contains("\"path\":\"a.txt\"")
                .contains("\"api_token\":\"***\"")
                .contains("\"password\":\"***\"")
                .contains("\"accessKey\":\"***\"")
                .doesNotContain("secret-value");
    }

    @Test
    void neverEchoesMalformedArgumentsBackToTheClient() {
        assertThat(ToolArgumentSanitizer.sanitize("token=plain-secret"))
                .isEqualTo("{\"redacted\":\"arguments unavailable\"}")
                .doesNotContain("plain-secret");
    }
}
