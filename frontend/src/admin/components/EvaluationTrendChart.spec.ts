import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import EvaluationTrendChart from './EvaluationTrendChart.vue'
import type { EvaluationHistoryItem } from '../api/evaluation-api'

function historyItem(overrides: Partial<EvaluationHistoryItem>): EvaluationHistoryItem {
  return { taskId: 't-1', status: 'SUCCESS', startedAtMillis: 1_700_000_000_000, completedCases: 10,
    totalCases: 10, passRate: 1, dimensionPassRates: {}, ...overrides }
}

describe('EvaluationTrendChart', () => {
  it('asks for more runs instead of drawing a one-point line', () => {
    const wrapper = mount(EvaluationTrendChart, { props: { history: [historyItem({})] } })
    expect(wrapper.find('svg').exists()).toBe(false)
    expect(wrapper.text()).toContain('Run the evaluation a couple more times')
  })

  it('plots one point per finished run and skips runs still in flight', () => {
    const history = [
      historyItem({ taskId: 't-3', startedAtMillis: 1_700_000_200_000, passRate: 0.9 }),
      historyItem({ taskId: 't-2', status: 'RUNNING', startedAtMillis: 1_700_000_100_000, passRate: 0 }),
      historyItem({ taskId: 't-1', startedAtMillis: 1_700_000_000_000, passRate: 0.8 })
    ]
    const wrapper = mount(EvaluationTrendChart, { props: { history } })

    expect(wrapper.findAll('circle')).toHaveLength(2)
  })

  it('colors a failed run differently from a successful one', () => {
    const history = [
      historyItem({ taskId: 't-2', status: 'FAILED', startedAtMillis: 1_700_000_100_000, passRate: 0 }),
      historyItem({ taskId: 't-1', startedAtMillis: 1_700_000_000_000, passRate: 1 })
    ]
    const wrapper = mount(EvaluationTrendChart, { props: { history } })

    const fills = wrapper.findAll('circle').map(circle => circle.attributes('fill'))
    expect(new Set(fills).size).toBe(2)
  })
})
