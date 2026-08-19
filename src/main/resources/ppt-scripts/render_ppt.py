"""AgentTrail PPT 渲染脚本（issue #24，Phase 6）。

Java 侧（PptPythonRenderer）通过 ProcessBuilder 调用本脚本，传入
--template/--schema/--output 三个文件路径，本脚本只做一件事："模板填充"：
按 schema JSON 里给出的 shapeName，在模板 pptx 里递归定位到对应文本框（含
Group shape 内部嵌套的文本框——这正是选 python-pptx 而不是 Java Apache POI
的原因，POI 对 Group shape 支持差，见 docs/engineering-pitfalls-and-highlights.md
踩坑点 #55），把文字替换进去。

模板约定（见 src/main/resources/ppt-templates/default-template.pptx）：
- 第 0 张幻灯片是标题页，由 schema 的 titleSlideFills 填充一次
- 第 1 张幻灯片是内容页模板，schema 的 contentSlides 有几项就渲染几张内容页——
  第一项直接填第 1 张，后续项先复制一份（duplicate_slide）再填，不修改原模板结构

fontLimit 是模型输出的软约束（Prompt 里会提示字数上限），但模型不一定严格遵守
（踩坑点 #53）——apply_fill 在这里做硬性截断兜底，不假设上游已经满足长度约束。

issue #33（Phase 6 素材生成增强）新增了一件事：给每张幻灯片右下角/标题页右上角贴一张
装饰性图片——标题页优先用 IMAGE 状态（issue #31）转存到 MinIO 的 AI 配图
（schema 的 coverImageUrl，经 RenderStrategy 翻译成这里读到的 titleSlideImageUrl），
没有配图或者下载失败时，退化成 ppt_asset_generator 生成的装饰性图形；内容页则总是贴
一张按标题文字派生的装饰性图形，不依赖任何配图 URL。选型理由（Pillow 而不是 SVG）见
ppt_asset_generator.py 顶部注释。
"""
import argparse
import copy
import json
import os
import sys
import tempfile
import urllib.error
import urllib.request

from pptx import Presentation
from pptx.enum.shapes import MSO_SHAPE_TYPE
from pptx.util import Inches

from ppt_asset_generator import generate_accent_graphic

TITLE_IMAGE_WIDTH_IN = 2.6
CONTENT_IMAGE_WIDTH_IN = 1.3
IMAGE_MARGIN_IN = 0.35


def find_shape_by_name(shapes, name):
    for shape in shapes:
        if shape.name == name:
            return shape
        if shape.shape_type == MSO_SHAPE_TYPE.GROUP:
            found = find_shape_by_name(shape.shapes, name)
            if found is not None:
                return found
    return None


def apply_fill(shapes, fill):
    shape_name = fill.get("shapeName")
    shape = find_shape_by_name(shapes, shape_name)
    if shape is None:
        print("[warn] shape not found, skip: %s" % shape_name, file=sys.stderr)
        return
    text = fill.get("text") or ""
    font_limit = fill.get("fontLimit")
    if isinstance(font_limit, int) and font_limit > 0 and len(text) > font_limit:
        text = text[:font_limit]
    if shape.has_text_frame:
        shape.text_frame.text = text
    else:
        print("[warn] shape has no text frame, skip: %s" % shape_name, file=sys.stderr)


def apply_speaker_notes(slide, notes):
    """把动态 Schema 的演讲备注落到真实 notes slide；旧模板没有备注时安全降级。"""
    if not notes:
        return
    try:
        slide.notes_slide.notes_text_frame.text = notes
    except AttributeError:
        print("[warn] template has no notes text frame, skip speaker notes", file=sys.stderr)


def _download_to_file(url, dest_path, timeout_seconds=20):
    request = urllib.request.Request(url, headers={"User-Agent": "AgentTrail-PPT-Render/1.0"})
    with urllib.request.urlopen(request, timeout=timeout_seconds) as response, open(dest_path, "wb") as out:
        out.write(response.read())


def _send_to_back(shape):
    """把装饰图形挪到 z-order 最底层，不盖住文字。

    模板里的文本框（title_text/subtitle_text/slide_title_text/slide_body_text）背景都是
    BACKGROUND（无填充，见 default-template.pptx），add_picture 默认贴在 spTree 最后一个位置
    （z-order 最上层，会挡住文字）；这里把新插入的图片元素挪到 nvGrpSpPr/grpSpPr 这两个必须
    在最前面的元素之后、第一个位置，让它排到所有已有 shape 后面——图形和标题/正文的定位框在
    位置上本来就会有重叠（都在角落附近），但因为文本框透明、图形又在更底层，视觉上是文字盖在
    装饰图形之上，不会出现文字被图形整个遮住看不清的问题。
    """
    sp_tree = shape._element.getparent()
    sp_tree.remove(shape._element)
    sp_tree.insert(2, shape._element)


def _place_corner_picture(prs, slide, image_path, width_inches, corner):
    # 先按目标宽度插入，图片原生宽高比由 python-pptx 自动算出对应高度（我们生成的装饰图形是
    # 正方形，AI 配图通常也是正方形，但这里不假设——不管什么比例，先插入再用真实 pic.width/
    # pic.height 摆位置都是对的，不用自己算宽高比
    pic = slide.shapes.add_picture(image_path, 0, 0, width=Inches(width_inches))
    margin = Inches(IMAGE_MARGIN_IN)
    pic.left = int(prs.slide_width - pic.width - margin)
    pic.top = int(margin) if corner == "top_right" else int(prs.slide_height - pic.height - margin)
    _send_to_back(pic)
    return pic


def _decorate_title_slide(prs, slide, title_text, image_url, temp_dir):
    """标题页配图：优先用 titleSlideImageUrl（issue #31 转存到 MinIO 的真实 AI 配图），
    没有配图或者下载失败时退化成 ppt_asset_generator 生成的装饰性图形兜底——不能因为一张
    锦上添花的图让整页看起来像渲染出错了（比如留一个破损的图片占位符）。这是渲染阶段的
    第二道降级防线：ImageStrategy 已经在配图失败时把 coverImageUrl 留成 null，但即使当时
    配图成功了，schema 落库到真正跑到这一步之间可能隔了很久（断点续传），MinIO 那张图这时候
    也可能已经不可达，所以这里不能假设"URL 不为空就一定能下载成功"。
    """
    image_path = None
    if image_url:
        candidate = os.path.join(temp_dir, "title-cover.png")
        try:
            _download_to_file(image_url, candidate)
            image_path = candidate
        except (urllib.error.URLError, OSError, ValueError) as download_failed:
            print("[warn] title cover image download failed, falling back to generated accent graphic: %s"
                  % download_failed, file=sys.stderr)
    if image_path is None:
        image_path = os.path.join(temp_dir, "title-accent.png")
        generate_accent_graphic(title_text or "agenttrail-ppt", image_path)
    _place_corner_picture(prs, slide, image_path, TITLE_IMAGE_WIDTH_IN, "top_right")


def _decorate_content_slide(prs, slide, seed_text, index, temp_dir):
    image_path = os.path.join(temp_dir, "content-accent-%d.png" % index)
    generate_accent_graphic(seed_text or ("slide-%d" % index), image_path)
    _place_corner_picture(prs, slide, image_path, CONTENT_IMAGE_WIDTH_IN, "bottom_right")


def duplicate_slide(prs, source_slide):
    layout = source_slide.slide_layout
    new_slide = prs.slides.add_slide(layout)
    for shp in list(new_slide.shapes):
        shp._element.getparent().remove(shp._element)
    for shp in source_slide.shapes:
        new_el = copy.deepcopy(shp._element)
        new_slide.shapes._spTree.append(new_el)
    return new_slide


def template_slide_for_ref(prs, template_ref, fallback):
    """按稳定 templatePageRef 选择版式；旧/未知引用退化到默认内容模板并告警。"""
    if not template_ref:
        return fallback
    normalized = str(template_ref).strip().lower()
    if normalized in ("content", "default", "content-1"):
        return fallback
    if normalized in ("cover", "title"):
        return prs.slides[0]
    for prefix in ("slide-", "index-"):
        if normalized.startswith(prefix):
            try:
                index = int(normalized[len(prefix):])
                if 0 <= index < len(prs.slides):
                    return prs.slides[index]
            except ValueError:
                pass
    print("[warn] template page ref not found, fallback: %s" % template_ref, file=sys.stderr)
    return fallback


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--template", required=True)
    parser.add_argument("--schema", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    with open(args.schema, "r", encoding="utf-8") as f:
        payload = json.load(f)

    prs = Presentation(args.template)
    if len(prs.slides) < 2:
        raise ValueError("模板至少需要 2 张幻灯片：第 0 张标题页，第 1 张内容模板页")

    title_slide = prs.slides[0]
    content_template_slide = prs.slides[1]

    for fill in payload.get("titleSlideFills", []):
        apply_fill(title_slide.shapes, fill)
    title_text_for_seed = next(
        (fill.get("text") for fill in payload.get("titleSlideFills", []) if fill.get("shapeName") == "title_text"),
        "")

    # 新版动态 Schema 保留 pageId/pageType/templateRef/notes；旧任务没有 pages 时继续使用
    # contentSlides。两条协议在渲染边界收敛，保证断点恢复的旧快照无需重新生成 Schema。
    dynamic_pages = payload.get("pages", [])
    if dynamic_pages:
        cover_page = dynamic_pages[0]
        for fill in cover_page.get("fills", []):
            apply_fill(title_slide.shapes, fill)
        apply_speaker_notes(title_slide, cover_page.get("speakerNotes"))
        title_text_for_seed = next(
            (fill.get("text") for fill in cover_page.get("fills", [])
             if fill.get("shapeName") == "title_text"), title_text_for_seed)
        content_slides_payload = [
            {"fills": page.get("fills", []), "speakerNotes": page.get("speakerNotes"),
             "templatePageRef": page.get("templatePageRef")}
            for page in dynamic_pages[1:]
        ]
    else:
        content_slides_payload = payload.get("contentSlides", [])

    # 复制幻灯片（duplicate_slide）和贴装饰图片（issue #33）必须分成两个独立的循环，不能在
    # 同一次遍历里对第 0 张内容页边填字边贴图——duplicate_slide 是把 content_template_slide
    # 当前的 shapes 原样深拷贝一份，如果第 0 张先被贴了图再去复制，后面每一张复制出来的内容页
    # 都会连带复制到第 0 张身上那张图，越往后的幻灯片图片越多，是这一票踩过的一个真实 bug。
    # 所以：第一个循环只管"复制 + 填字"，此时 content_template_slide 还没有被贴过任何图片；
    # 全部复制完成之后，第二个循环才逐张贴装饰图片，互不干扰。
    target_slides = []
    used_template_ids = set()
    for index, slide_payload in enumerate(content_slides_payload):
        source_slide = template_slide_for_ref(prs, slide_payload.get("templatePageRef"), content_template_slide)
        source_id = id(source_slide)
        target_slide = source_slide if source_id not in used_template_ids else duplicate_slide(prs, source_slide)
        used_template_ids.add(source_id)
        for fill in slide_payload.get("fills", []):
            apply_fill(target_slide.shapes, fill)
        apply_speaker_notes(target_slide, slide_payload.get("speakerNotes"))
        target_slides.append(target_slide)

    with tempfile.TemporaryDirectory(prefix="agenttrail-ppt-assets-") as temp_dir:
        _decorate_title_slide(prs, title_slide, title_text_for_seed, payload.get("titleSlideImageUrl"), temp_dir)

        for index, (slide_payload, target_slide) in enumerate(zip(content_slides_payload, target_slides)):
            seed_text = next(
                (fill.get("text") for fill in slide_payload.get("fills", [])
                 if fill.get("shapeName") == "slide_title_text"),
                "")
            _decorate_content_slide(prs, target_slide, seed_text, index, temp_dir)

        # add_picture 在调用的当下就把图片字节写进了 pptx 的内存模型里，prs.save() 之前
        # temp_dir 不能提前清理；save 完成后临时文件就不再需要，随 with 块退出自动删除
        prs.save(args.output)

    print("OK %s" % args.output)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("[error] %s" % exc, file=sys.stderr)
        sys.exit(1)
