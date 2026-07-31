package com.agenttrail.loop.file;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

import java.io.IOException;
import java.io.InputStream;

/**
 * 统一的文件文本解析（issue #21）：一个 {@link Tika} 门面覆盖 PDF/Office 全家桶/HTML/纯文本
 * 等几十种格式，不用像参考实现较旧的一版那样为每种格式各写一套 PDFBox/POI 解析分支。
 */
public class FileTextParser {

    private final Tika tika = new Tika();

    /**
     * 解析文件内容为纯文本。
     *
     * @param content  文件的原始字节流，调用方负责关闭
     * @param fileName 仅用于失败时的错误信息，不参与解析
     * @return 解析出的纯文本，去除首尾空白；未提取到任何文本时返回空字符串（不是 null）
     */
    public String parse(InputStream content, String fileName) {
        try {
            String text = tika.parseToString(content);
            return text == null ? "" : text.trim();
        } catch (IOException | TikaException failure) {
            throw new FileParsingException("文件解析失败：" + fileName, failure);
        }
    }
}
