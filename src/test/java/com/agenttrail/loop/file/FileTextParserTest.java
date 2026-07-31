package com.agenttrail.loop.file;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FileTextParserTest {

    private final FileTextParser parser = new FileTextParser();

    @Test
    void parsesPlainText() {
        String text = parser.parse(
                new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)), "sample.txt");

        assertThat(text).isEqualTo("hello world");
    }
}
