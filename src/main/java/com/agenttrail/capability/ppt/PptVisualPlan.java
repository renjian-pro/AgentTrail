package com.agenttrail.capability.ppt;

/**
 * 全局视觉规划。模板、Schema 和素材阶段读取同一份对象，避免每个 Strategy 各自猜风格；
 * 它随上下文 checkpoint 持久化，而不是只存在某次 Prompt 的局部变量里。
 */
public record PptVisualPlan(String styleKeywords, String primaryColor, String secondaryColor,
        String backgroundColor, String titleFont, String bodyFont, String imageStyle,
        String pageDensity, String chartStyle, String pageSize, String language) {
    public PptVisualPlan {
        if (styleKeywords == null || styleKeywords.isBlank() || primaryColor == null || primaryColor.isBlank()
                || backgroundColor == null || backgroundColor.isBlank() || titleFont == null || titleFont.isBlank()
                || bodyFont == null || bodyFont.isBlank() || pageSize == null || pageSize.isBlank()
                || language == null || language.isBlank()) {
            throw new IllegalArgumentException("PPT visual plan has required fields missing");
        }
    }

    public static PptVisualPlan defaultFor(PptRequirement requirement) {
        String language = requirement != null && requirement.title() != null
                && requirement.title().matches(".*[\\u4e00-\\u9fff].*") ? "zh-CN" : "en-US";
        return new PptVisualPlan("clean, professional, readable", "#2563EB", "#14B8A6", "#F8FAFC",
                "Aptos Display", "Aptos", "minimal editorial illustration", "balanced", "minimal",
                "16:9", language);
    }
}
