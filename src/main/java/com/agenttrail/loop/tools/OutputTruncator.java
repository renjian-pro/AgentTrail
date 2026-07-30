package com.agenttrail.loop.tools;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 命令输出截断。
 *
 * <p>为什么要截断：约束不是 JVM 内存，而是**模型的上下文预算**。一条 {@code find /} 或者一次
 * 疯狂刷屏的构建日志，几十万行原样喂回去，一次就能把上下文顶爆，后面的对话全部废掉。
 * 所以 stdout 和 stderr 都要限长——错误输出刷屏的概率并不比正常输出低（编译错误、堆栈）。
 *
 * <p>两条限制同时生效，而不是命中一条就返回：行数限制挡"很多短行"，字节限制挡"很少的超长行"，
 * 少了任何一条都有绕过的形状。参考实现命中行数限制就直接 return 了，一行 10MB 的输出照样过。
 */
final class OutputTruncator {

    private final int maxLines;
    private final int maxBytes;
    private final Charset charset;

    OutputTruncator(int maxLines, int maxBytes, Charset charset) {
        this.maxLines = maxLines;
        this.maxBytes = maxBytes;
        this.charset = charset;
    }

    String truncate(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String result = text;
        List<String> reasons = new ArrayList<>();

        String[] lines = result.split("\n", -1);
        if (lines.length > maxLines) {
            result = String.join("\n", Arrays.copyOfRange(lines, 0, maxLines));
            reasons.add("超过 " + maxLines + " 行");
        }

        if (result.getBytes(charset).length > maxBytes) {
            result = cutToByteLimit(result);
            reasons.add("超过 " + maxBytes + " 字节");
        }

        if (reasons.isEmpty()) {
            return result;
        }
        return result + "\n... [输出已截断：" + String.join("、", reasons) + "]";
    }

    /**
     * 按**完整字符**截到字节上限以内。
     *
     * <p>不能直接 {@code new String(bytes, 0, maxBytes, charset)}：那样会把最后一个多字节字符
     * 劈成两半，模型收到的是一个替换字符（U+FFFD）。用 {@link CharsetEncoder} 编码到一个定长
     * 缓冲区，编码器天然停在字符边界上，再按它消费掉的字符数回切原字符串。
     */
    private String cutToByteLimit(String text) {
        CharsetEncoder encoder = charset.newEncoder();
        CharBuffer input = CharBuffer.wrap(text);
        ByteBuffer output = ByteBuffer.allocate(maxBytes);
        encoder.encode(input, output, true);
        return text.substring(0, input.position());
    }
}
