package com.agenttrail.loop.multimodal;

import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 {@code qwen3-vl-plus} 调用跑通图片描述（issue #27 验收标准，不接受 mock）。
 * 图片是测试内生成的合成图（红色圆形画在白底上），不依赖仓库外的素材文件；
 * 只断言描述非空、不是占位提示——多模态模型的具体措辞不适合做精确断言，
 * 断言过细反而是在测"这次模型恰好怎么说"，不是测这条链路本身对不对。
 */
@SpringBootTest
class ImageDescriptionIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedMySql::jdbcUrl);
        registry.add("spring.datasource.username", SharedMySql::username);
        registry.add("spring.datasource.password", SharedMySql::password);
    }

    @Autowired
    private ImageDescriptionService imageDescriptionService;

    @Test
    void describesARealSyntheticImageUsingTheRealVisionModel() {
        byte[] png = redCircleOnWhitePng();

        String description = imageDescriptionService.describe(png, "circle.png");

        assertThat(description).isNotBlank();
        assertThat(description).doesNotContain("[图片内容为空]").doesNotContain("[无法识别图片内容]");
    }

    private static byte[] redCircleOnWhitePng() {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 200, 200);
        graphics.setColor(Color.RED);
        graphics.fillOval(50, 50, 100, 100);
        graphics.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
