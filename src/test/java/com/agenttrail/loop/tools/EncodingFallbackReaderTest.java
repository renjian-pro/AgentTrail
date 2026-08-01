package com.agenttrail.loop.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EncodingFallbackReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void prefersUtf8AndCanSplitTheDecodedTextIntoLines() throws Exception {
        Path file = tempDir.resolve("utf8.txt");
        Files.writeString(file, "第一行\nsecond line", StandardCharsets.UTF_8);

        EncodingFallbackReader.Decoded decoded = EncodingFallbackReader.read(file);

        assertThat(decoded.text()).isEqualTo("第一行\nsecond line");
        assertThat(decoded.charset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(EncodingFallbackReader.readLines(file)).containsExactly("第一行", "second line");
    }

    @Test
    void fallsBackToGbkForLegacyChineseFiles() throws Exception {
        Charset gbk = Charset.forName("GBK");
        Path file = tempDir.resolve("gbk.txt");
        Files.write(file, "中文内容".getBytes(gbk));

        EncodingFallbackReader.Decoded decoded = EncodingFallbackReader.read(file);

        assertThat(decoded.text()).isEqualTo("中文内容");
        assertThat(decoded.charset()).isEqualTo(gbk);
    }

    @Test
    void usesIso88591AsTheLastResortForBytesInvalidInUtf8AndGbk() throws Exception {
        Path file = tempDir.resolve("binary-like.txt");
        Files.write(file, new byte[]{(byte) 0x81});

        EncodingFallbackReader.Decoded decoded = EncodingFallbackReader.read(file);

        assertThat(decoded.text()).isEqualTo("\u0081");
        assertThat(decoded.charset()).isEqualTo(StandardCharsets.ISO_8859_1);
    }
}
