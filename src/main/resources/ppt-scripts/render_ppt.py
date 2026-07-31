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
"""
import argparse
import copy
import json
import sys

from pptx import Presentation
from pptx.enum.shapes import MSO_SHAPE_TYPE


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


def duplicate_slide(prs, source_slide):
    layout = source_slide.slide_layout
    new_slide = prs.slides.add_slide(layout)
    for shp in list(new_slide.shapes):
        shp._element.getparent().remove(shp._element)
    for shp in source_slide.shapes:
        new_el = copy.deepcopy(shp._element)
        new_slide.shapes._spTree.append(new_el)
    return new_slide


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

    content_slides_payload = payload.get("contentSlides", [])
    for index, slide_payload in enumerate(content_slides_payload):
        target_slide = content_template_slide if index == 0 else duplicate_slide(prs, content_template_slide)
        for fill in slide_payload.get("fills", []):
            apply_fill(target_slide.shapes, fill)

    prs.save(args.output)
    print("OK %s" % args.output)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("[error] %s" % exc, file=sys.stderr)
        sys.exit(1)
