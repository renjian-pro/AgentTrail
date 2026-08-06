import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import GoldenCasesView from './GoldenCasesView.vue'
import { evaluationApi } from '../api/evaluation-api'

vi.mock('../api/evaluation-api', () => ({
  evaluationApi: { cases: vi.fn(), createCase: vi.fn(), updateCase: vi.fn(), deleteCase: vi.fn() }
}))

const BUILTIN = { id: 'sql-001', dimension: 'sql_correctness', question: 'count rentals', asUser: 'admin',
  referenceSql: 'SELECT COUNT(*) FROM rental', assertions: [{ type: 'result_matches_reference' }],
  expectedToolCalls: [], source: 'BUILTIN' as const, sourceConversationId: null, editable: false,
  createdAtMillis: 0, updatedAtMillis: 0 }
const MANUAL = { ...BUILTIN, id: 'manual-1', source: 'MANUAL' as const, editable: true }

describe('GoldenCasesView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(evaluationApi.cases).mockResolvedValue([BUILTIN, MANUAL])
  })

  it('lists cases and marks built-in ones read-only', async () => {
    const wrapper = mount(GoldenCasesView)
    await flushPromises()

    expect(wrapper.text()).toContain('sql-001')
    expect(wrapper.text()).toContain('manual-1')
    expect(wrapper.text()).toContain('read-only')
  })

  it('creates a new case from the form and reloads the list', async () => {
    vi.mocked(evaluationApi.createCase).mockResolvedValue({ ...MANUAL, id: 'manual-2' })
    const wrapper = mount(GoldenCasesView)
    await flushPromises()

    await wrapper.find('button.primary-button').trigger('click')
    await wrapper.find('input[list="golden-dimensions"]').setValue('sql_correctness')
    await wrapper.find('textarea').setValue('count rentals again')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(evaluationApi.createCase).toHaveBeenCalledWith(expect.objectContaining({
      dimension: 'sql_correctness', question: 'count rentals again'
    }))
    expect(evaluationApi.cases).toHaveBeenCalledTimes(2)
  })

  it('deletes an editable case after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(evaluationApi.deleteCase).mockResolvedValue(undefined)
    const wrapper = mount(GoldenCasesView)
    await flushPromises()

    const deleteButton = wrapper.findAll('button.danger-button')[0]
    await deleteButton.trigger('click')
    await flushPromises()

    expect(evaluationApi.deleteCase).toHaveBeenCalledWith('manual-1')
  })

  it('rejects assertions that are not valid JSON instead of silently dropping them', async () => {
    const wrapper = mount(GoldenCasesView)
    await flushPromises()

    await wrapper.find('button.primary-button').trigger('click')
    await wrapper.find('input[list="golden-dimensions"]').setValue('sql_correctness')
    await wrapper.find('textarea').setValue('count rentals again')
    await wrapper.findAll('textarea')[2].setValue('not json')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(evaluationApi.createCase).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('JSON array')
  })
})
