"""生成用于点缀 PPT 页面的装饰性图形素材（issue #33，Phase 6 可选增强：Pillow/SVG 素材生成）。

## 选型结论：Pillow，不是 SVG

两条路线都能做出比纯文字模板更好看的配图，权衡点在"要不要多引入一个系统级依赖"：

- Pillow：命令式（一笔一笔画），写起来比声明式的 SVG 啰嗦一点，但纯 Python wheel，
  零系统依赖。这个项目已经因为 render_ppt.py/python-pptx 而在部署环境里装好了一份
  Python 运行时——Pillow 是这份运行时上的又一个 `pip install`，不新增任何东西。
- SVG：声明式、画质更高（矢量、抗锯齿更好），但要把 SVG 光栅化成 PPT 里能嵌入的位图
  （python-pptx 不支持直接把 SVG 作为图片插入，pptx 格式本身对 SVG 图片的支持也依赖
  PowerPoint 版本），标准做法要装 cairosvg/pycairo，这两个包在 Windows/Linux 上都需要
  系统里先有 libcairo 动态库——不是 `pip install` 能兜底的纯 Python 依赖。这个仓库目前
  没有任何地方触碰过 cairo，一旦漏装，报错会发生在生产部署环境而不是本地开发机器上，
  排查成本明显更高，而这一票的验收标准只要求"至少支持一种素材类型"，不需要 SVG 那种
  更高画质去换这个额外的部署负担。

结论：选 Pillow。这个模块只实现一种素材类型（验收标准里举的三个例子之一）：
"装饰性色块图形"——不理解文字语义，纯粹用标题/正文哈希出来的确定性构图给原本
清一色纯文字的模板页面加一点视觉层次，成本最低、最不依赖任何额外的模型调用。

## 为什么用哈希而不是随机数

`generate_accent_graphic` 按 `seed_text`（每张幻灯片自己的标题文字）确定性地派生
颜色/位置/大小，不用 `random`/系统时钟起种子——好处有两个：一是同一份 PPT 断点续传
重新跑 RENDER 状态时，装饰图形长得完全一样，不会出现"重跑一次配图风格全变了"这种
让人困惑的不确定性；二是这个函数本身可以被离线单元测试：同一个 seed_text 两次调用
产出字节相同，不需要在测试里对"图形好不好看"做模糊断言。
"""
import hashlib

from PIL import Image, ImageDraw

# 几组预设的双色搭配，按 seed_text 哈希的第一个字节选一组——不做成完全随机的调色板生成，
# 避免出现两个颜色对比度太低导致叠加后看不清层次的组合
_PALETTES = [
    ((66, 133, 244), (52, 168, 83)),
    ((234, 67, 53), (251, 188, 5)),
    ((156, 39, 176), (0, 172, 193)),
    ((255, 112, 67), (38, 166, 154)),
]

# Pillow 的 ImageDraw 画圆/矩形本身没有抗锯齿（边缘是硬像素台阶），先在放大 N 倍的画布上画，
# 再用 LANCZOS 缩小回目标尺寸，是不引入额外依赖（比如 aggdraw）就能拿到平滑边缘的最简单办法
_SUPERSAMPLE = 4


def generate_accent_graphic(seed_text, output_path, size_px=480):
    """按 seed_text 生成一张正方形、带透明背景的装饰性图形 PNG，写到 output_path。

    构图是 3 个大小/位置/颜色各不相同、相互叠加的半透明圆——数量和形状故意保持简单，
    这一票的目标是"给纯文字模板加一点视觉层次"，不是做一个通用的图形设计引擎。
    """
    digest = hashlib.md5(seed_text.encode("utf-8")).digest()
    palette = _PALETTES[digest[0] % len(_PALETTES)]

    big = size_px * _SUPERSAMPLE
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)

    for i in range(3):
        # digest 是 16 字节的 md5，offset 最大取到 7+2=9，留在合法范围内
        offset = 1 + i * 3
        color = palette[i % len(palette)]
        radius_ratio = 0.28 + (digest[offset] / 255.0) * 0.22
        cx_ratio = 0.3 + (digest[offset + 1] / 255.0) * 0.4
        cy_ratio = 0.3 + (digest[offset + 2] / 255.0) * 0.4
        radius = int(big * radius_ratio)
        cx = int(big * cx_ratio)
        cy = int(big * cy_ratio)
        alpha = min(150 + i * 30, 235)
        draw.ellipse(
            [cx - radius, cy - radius, cx + radius, cy + radius],
            fill=(color[0], color[1], color[2], alpha),
        )

    canvas = canvas.resize((size_px, size_px), Image.LANCZOS)
    canvas.save(output_path, format="PNG")
