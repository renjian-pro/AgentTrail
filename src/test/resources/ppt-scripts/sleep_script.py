"""PptPythonRendererTest 用的固定脚本：睡得比测试给的超时时间长，模拟"渲染进程卡死"，
用来验证 Java 侧用 waitFor(timeout, unit) 能正确判定超时并强制终止进程。"""
import sys
import time

time.sleep(30)
sys.exit(0)
