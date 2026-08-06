import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import GoldenCandidatesView from './GoldenCandidatesView.vue'
import { evaluationApi } from '../api/evaluation-api'

vi.mock('../api/evaluation-api', () => ({
  evaluationApi: { conversations: vi.fn(), candidates: vi.fn(), createCase: vi.fn() }
}))

describe('GoldenCandidatesView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(evaluationApi.conversations).mockResolvedValue({
      page: 0, size: 20, hasMore: false,
      sessions: [{ conversationId: 'conv-1', title: 'count rentals', lastActiveAtMillis: 1_700_000_000_000 }]
    })
    vi.mocked(evaluationApi.candidates).mockResolvedValue([
      { conversationId: 'conv-1', round: 1, question: 'count rentals', actualOutput: '42 rentals',
        recordedAtMillis: 1_700_000_000_000, reviewStatus: 'PENDING_HUMAN_CONFIRMATION' }
    ])
  })

  it('lists conversations and loads candidates on selection', async () => {
    const wrapper = mount(GoldenCandidatesView)
    await flushPromises()

    expect(wrapper.text()).toContain('count rentals')
    await wrapper.find('.candidate-list-item').trigger('click')
    await flushPromises()

    expect(evaluationApi.candidates).toHaveBeenCalledWith('conv-1')
    expect(wrapper.text()).toContain('42 rentals')
  })

  it('opens the promote dialog prefilled from the candidate and submits it as a PROMOTED case', async () => {
    vi.mocked(evaluationApi.createCase).mockResolvedValue({
      id: 'promoted-1', dimension: 'sql_correctness', question: 'count rentals', asUser: 'admin',
      referenceSql: null, assertions: [], expectedToolCalls: [], source: 'PROMOTED',
      sourceConversationId: 'conv-1', editable: true, createdAtMillis: 0, updatedAtMillis: 0
    })
    const wrapper = mount(GoldenCandidatesView)
    await flushPromises()
    await wrapper.find('.candidate-list-item').trigger('click')
    await flushPromises()

    await wrapper.find('details button.primary-button').trigger('click')
    await flushPromises()

    expect(wrapper.find('textarea').element.value).toBe('count rentals')
    expect(wrapper.text()).toContain('42 rentals')

    await wrapper.find('input[list="golden-dimensions"]').setValue('sql_correctness')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(evaluationApi.createCase).toHaveBeenCalledWith(expect.objectContaining({
      source: 'PROMOTED', sourceConversationId: 'conv-1', question: 'count rentals'
    }))
  })
})
