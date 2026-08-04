import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import SqlToolCard from './SqlToolCard.vue'

describe('SqlToolCard', () => {
  it('renders formatted SQL, result rows, masked values and copy feedback', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const wrapper = mount(SqlToolCard, {
      props: {
        name: 'execute_sql',
        argumentsText: JSON.stringify({ sql: 'SELECT id_card FROM user_profile' }),
        result: '查询成功，共 1 行（耗时 3ms）。\n\n| id_card |\n|---|\n| ******** |'
      }
    })

    expect(wrapper.get('details').attributes('open')).toBeDefined()
    expect(wrapper.get('.sql-code').text()).toContain('SELECT id_card')
    expect(wrapper.get('table').text()).toContain('🔒 ********')
    expect(wrapper.get('.is-masked').attributes('title')).toBe('该字段已脱敏')

    await wrapper.get('.sql-code-head button').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('SELECT id_card'))
    expect(wrapper.get('.sql-code-head button').text()).toBe('已复制')
  })

  it('keeps empty and error results out of the table branch', () => {
    const empty = mount(SqlToolCard, {
      props: { name: 'execute_sql', argumentsText: '{"sql":"SELECT 1"}', result: '查询执行成功，但没有匹配数据。' }
    })
    expect(empty.find('table').exists()).toBe(false)
    expect(empty.text()).toContain('没有匹配数据')

    const error = mount(SqlToolCard, {
      props: { name: 'validate_sql', argumentsText: '{"sql":"DROP TABLE rental"}', result: '校验未通过：只允许 SELECT' }
    })
    expect(error.find('table').exists()).toBe(false)
    expect(error.find('.sql-status-error').text()).toContain('校验未通过')
  })
})
