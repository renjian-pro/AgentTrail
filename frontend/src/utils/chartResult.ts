/**
 * mcp-echarts 在一个 MCP 端点上挂着十几个动态命名的工具（generate_bar_chart /
 * generate_line_chart / generate_echarts …），后端靠 `instanceof ChartToolCallback` 识别它们、
 * 明确不按工具名匹配（见 ChartToolProvider 的类注释）。前端看不到 Java 类型，等价的稳健信号
 * 是**结果形状**：图表工具的返回值就是一整段图片 URL，没有任何别的文字。
 *
 * 因此这里刻意收紧——必须整段就是 http(s) 图片链接才认，混在文字里的链接、非图片链接、
 * 伪协议一律不认，避免把别的工具的输出错渲染成 <img>。
 */
const BARE_IMAGE_URL = /^https?:\/\/[^\s]+\.(?:png|jpe?g|svg|webp|gif)$/i

/** @returns 可以直接放进 <img src> 的地址；不是图表图片结果时返回 undefined */
export function chartImageUrl(result?: string): string | undefined {
  const trimmed = result?.trim()
  return trimmed && BARE_IMAGE_URL.test(trimmed) ? trimmed : undefined
}
