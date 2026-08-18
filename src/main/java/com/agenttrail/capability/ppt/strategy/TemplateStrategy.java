package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationException;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptTemplateRegistry;
import com.agenttrail.capability.ppt.PptTemplateVersion;
import com.agenttrail.capability.ppt.PptVisualPlan;
import com.agenttrail.capability.ppt.PptTemplateRef;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TEMPLATE 状态（issue #24）——这一票只有一份固定模板（{@code default-template.pptx}），
 * 路径来自配置（不是硬编码绝对路径，见 {@code PptGenerationConfig} 的
 * {@code agenttrail.ppt.template-path}），这个状态的职责就是把它写进上下文、顺带校验文件
 * 真的存在（早失败，不要等到 RENDER 状态才发现模板路径配错了）。
 *
 * <p>"按需求选不同模板"不在这一票范围内——真要做，这里会变成"按 requirement 的某个字段
 * 查模板注册表"，接口形状不用变。
 */
public class TemplateStrategy implements PptGenerationStrategy {

    private final String templatePath;
    private final Path allowlistDir;
    private final PptTemplateRegistry templateRegistry;
    private final String templateId;
    private final String templateVersion;

    public TemplateStrategy(String templatePath) {
        this(templatePath, Path.of(templatePath).toAbsolutePath().normalize().getParent().toString(), null,
                "default", "1");
    }

    public TemplateStrategy(String templatePath, String allowlistDir) {
        this(templatePath, allowlistDir, null, "default", "1");
    }

    /** 生产装配使用注册表；旧测试/调用方可以继续使用不带 registry 的构造函数。 */
    public TemplateStrategy(String templatePath, String allowlistDir, PptTemplateRegistry templateRegistry) {
        this(templatePath, allowlistDir, templateRegistry, "default", "1");
    }

    public TemplateStrategy(String templatePath, String allowlistDir, PptTemplateRegistry templateRegistry,
            String templateId, String templateVersion) {
        this.templatePath = templatePath;
        this.allowlistDir = Path.of(allowlistDir).toAbsolutePath().normalize();
        this.templateRegistry = templateRegistry;
        this.templateId = templateId;
        this.templateVersion = templateVersion;
    }

    @Override
    public PptState handledState() {
        return PptState.TEMPLATE;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        Path candidate = Path.of(templatePath).toAbsolutePath().normalize();
        if (!candidate.startsWith(allowlistDir) || !Files.isRegularFile(candidate)) {
            throw new PptGenerationException("PPT 模板文件不存在: " + templatePath);
        }
        PptGenerationContext next = context.withTemplatePath(candidate.toString());
        if (templateRegistry == null) {
            // 兼容旧配置：没有注册表时仍固定当前路径，但不会伪造一个版本写进上下文。
            return next;
        }
        PptTemplateVersion registered = templateRegistry.validate(templateId, templateVersion);
        if (!candidate.equals(Path.of(registered.templatePath()).toAbsolutePath().normalize())) {
            throw new PptGenerationException("PPT 模板路径与注册版本不一致: " + templateId + "/" + templateVersion);
        }
        return next.withTemplateRef(PptTemplateRef.from(registered))
                .withVisualPlan(context.visualPlan() == null
                        ? PptVisualPlan.defaultFor(context.requirement()) : context.visualPlan());
    }
}
