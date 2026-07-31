"""PptPythonRendererTest 用的固定脚本：不管参数，直接打印到 stderr 并以非零退出码退出，
模拟"渲染脚本执行失败"。"""
import sys

print("[error] simulated render failure", file=sys.stderr)
sys.exit(1)
