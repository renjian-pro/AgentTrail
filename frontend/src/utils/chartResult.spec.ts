import { describe, expect, it } from 'vitest'
import { chartImageUrl } from './chartResult'

describe('chartImageUrl', () => {
  it('accepts the bare image URL mcp-echarts returns', () => {
    expect(chartImageUrl('https://47.94.147.31/agenttrail-charts/charts/1786814298058.png'))
      .toBe('https://47.94.147.31/agenttrail-charts/charts/1786814298058.png')
    expect(chartImageUrl('  https://example.test/a.svg  ')).toBe('https://example.test/a.svg')
    expect(chartImageUrl('http://example.test/a.jpeg')).toBe('http://example.test/a.jpeg')
  })

  /** 工具名是动态的（generate_bar_chart/generate_echarts/…），只能靠结果形状认，所以边界必须收紧。 */
  it('rejects anything that is not a bare image URL', () => {
    expect(chartImageUrl(undefined)).toBeUndefined()
    expect(chartImageUrl('')).toBeUndefined()
    expect(chartImageUrl('查询成功，共 3 行')).toBeUndefined()
    expect(chartImageUrl('https://example.test/report.html')).toBeUndefined()
    expect(chartImageUrl('见 https://example.test/a.png 这张图')).toBeUndefined()
    expect(chartImageUrl('javascript:alert(1)//a.png')).toBeUndefined()
  })
})
