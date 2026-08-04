package com.agenttrail.loop.file;

import java.util.List;

/**
 * 把一个会话已上传的文件格式化成注入 prompt 用的文本块（issue #28）。两份参考实现都没做这个：
 * 同一轮上传多个文件时，如果只是把它们和历史文件混在一起列一遍平铺列表，模型很容易把
 * "这一轮新传的" 和 "很久以前传的" 混为一谈——按 {@code turnId} 是否已回填分成两组，
 * 让"本轮"这个边界在 prompt 里显式可见。
 *
 * <pre>
 * # 会话文件
 *
 * ## 本轮上传的文件
 * - report.pdf（fileId=12，文本）
 *
 * ## 此前上传的文件
 * - photo.png（fileId=7，图片）
 * </pre>
 */
public final class FilePromptFormatter {

    private FilePromptFormatter() {
    }

    /**
     * @return 没有文件时也返回非空文本——沉默（空字符串）会被模型当成"不确定"，不是"确实没有"，
     *         实测会导致模型为了回答数据/图表类问题去猜一个不存在的 fileId 调用
     *         {@code load_file_content}（比如问"今天几号"都会猜 fileId=1）。显式声明"当前
     *         没有文件、不要调用这个工具"，把沉默换成明确的否定信号。
     */
    public static String formatSection(List<UploadedFile> files) {
        if (files == null || files.isEmpty()) {
            return "# 会话文件\n\n当前会话没有已上传的文件，不要调用 load_file_content。\n";
        }

        List<UploadedFile> currentRound = files.stream().filter(file -> file.turnId() == null).toList();
        List<UploadedFile> priorRounds = files.stream().filter(file -> file.turnId() != null).toList();

        StringBuilder section = new StringBuilder("# 会话文件\n\n");
        section.append("以下文件的内容必须通过 load_file_content 工具获取，禁止在没有调用工具的情况下");
        section.append("凭空回答和文件相关的问题。\n");
        appendGroup(section, "本轮上传的文件", currentRound);
        appendGroup(section, "此前上传的文件", priorRounds);
        return section.toString();
    }

    private static void appendGroup(StringBuilder section, String heading, List<UploadedFile> files) {
        if (files.isEmpty()) {
            return;
        }
        section.append("\n## ").append(heading).append('\n');
        for (UploadedFile file : files) {
            section.append("- ").append(file.fileName())
                    .append("（fileId=").append(file.id())
                    .append(file.kind() == FileKind.IMAGE ? "，图片" : "，文本")
                    .append("）\n");
        }
    }
}
