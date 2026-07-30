package com.agenttrail.loop.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 三级编码兜底读取：UTF-8 → GBK → ISO-8859-1（踩坑点 #19）。
 *
 * <p>为什么需要：Windows 中文环境下，文件编码可能是这三者中的任意一种，硬编码 UTF-8
 * 读取会抛 {@code MalformedInputException}——工具直接报错，模型看到的是"读不了这个文件"，
 * 但它并没有能力去修编码问题，整条任务链就断在这。
 *
 * <p>这是一条**启发式尝试链**，不是真正的编码检测（真检测要 ICU4J 那种统计模型）。
 * 权衡点在于：GBK 几乎能解码任意字节序列而不报错，所以顺序很重要——先试最严格的 UTF-8，
 * 它对非法序列会立刻抛错；ISO-8859-1 是单字节编码，必然不抛异常，放在最后当兜底。
 *
 * <p>参考实现把这条链在文件工具和搜索工具里各抄了一份，且两处行为不一致
 * （编辑操作压根没走兜底）。这里收成一个类，谁读文件都走同一条链。
 */
final class EncodingFallbackReader {

    private static final Logger log = LoggerFactory.getLogger(EncodingFallbackReader.class);

    private static final Charset GBK = charsetOrNull("GBK");

    private EncodingFallbackReader() {
    }

    /** 读取结果：除了正文，还要带上"实际用了哪种编码"——写回时必须按原编码写，否则等于静默转码。 */
    record Decoded(String text, Charset charset) {
    }

    static Decoded read(Path file) throws IOException {
        try {
            return new Decoded(Files.readString(file, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        } catch (CharacterCodingException notUtf8) {
            log.debug("文件不是 UTF-8，改试 GBK：{}", file);
        }

        if (GBK != null) {
            try {
                return new Decoded(Files.readString(file, GBK), GBK);
            } catch (CharacterCodingException notGbk) {
                log.debug("文件不是 GBK，退到 ISO-8859-1：{}", file);
            }
        }

        // 单字节编码，任何字节序列都能解出字符，不会抛异常——这是兜底的最后一级
        byte[] bytes = Files.readAllBytes(file);
        return new Decoded(new String(bytes, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1);
    }

    static List<String> readLines(Path file) throws IOException {
        return read(file).text().lines().toList();
    }

    private static Charset charsetOrNull(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException unavailable) { // UnsupportedCharsetException / IllegalCharsetNameException 都是它的子类
            log.debug("当前 JDK 不支持字符集 {}，跳过该级兜底", name);
            return null;
        }
    }
}
