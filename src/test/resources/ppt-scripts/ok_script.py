"""PptPythonRendererTest 用的固定脚本：忽略参数语义，只按位置取 --output 后面那个值写一个
文件进去，模拟"渲染成功"。不依赖 python-pptx，跑得快、不需要真实模板。"""
import sys

args = sys.argv[1:]
output_path = args[args.index("--output") + 1]
with open(output_path, "w", encoding="utf-8") as f:
    f.write("rendered")
print("OK")
sys.exit(0)
