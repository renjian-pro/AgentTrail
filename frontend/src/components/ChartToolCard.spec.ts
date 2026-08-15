import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ChartToolCard from './ChartToolCard.vue'

const url = 'https://47.94.147.31/agenttrail-charts/charts/1786814298058.png'

describe('ChartToolCard', () => {
  it('renders the chart as an image titled by the tool arguments', () => {
    const wrapper = mount(ChartToolCard, {
      props: {
        name: 'generate_bar_chart',
        argumentsText: JSON.stringify({ title: '电影销量排行 TOP 10', data: [] }),
        result: url
      }
    })

    const image = wrapper.get('img.chart-image')
    expect(image.attributes('src')).toBe(url)
    expect(image.attributes('alt')).toBe('电影销量排行 TOP 10')
    expect(wrapper.get('a.chart-open').attributes()).toMatchObject({
      href: url, target: '_blank', rel: 'noopener noreferrer'
    })
  })

  it('falls back to the raw URL when the image cannot be loaded', async () => {
    const wrapper = mount(ChartToolCard, {
      props: { name: 'generate_pie_chart', argumentsText: '{}', result: url }
    })

    await wrapper.get('img.chart-image').trigger('error')

    expect(wrapper.find('img.chart-image').exists()).toBe(false)
    expect(wrapper.get('.chart-fallback').text()).toContain(url)
  })

  it('shows a placeholder while the tool is still running', () => {
    const wrapper = mount(ChartToolCard, {
      props: { name: 'generate_line_chart', argumentsText: '{}' }
    })

    expect(wrapper.find('img.chart-image').exists()).toBe(false)
    expect(wrapper.text()).toContain('生成图表中')
  })
})
