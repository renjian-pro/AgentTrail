import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PptTaskCard from './PptTaskCard.vue'
import { pptApi } from '../api/ppt-api'

vi.mock('../api/ppt-api', () => ({
  pptApi: { create: vi.fn(), resume: vi.fn(), status: vi.fn(), clarify: vi.fn() }
}))

const awaiting = (clarifyingQuestion: string) => ({
  kind: 'ppt' as const,
  prompt: '什么情况',
  task: { taskId: 7, status: 'AWAITING_INPUT', errorMsg: null, outputPath: null, clarifyingQuestion }
})

describe('PptTaskCard 需求澄清', () => {
  beforeEach(() => vi.clearAllMocks())

  /**
   * 追问必须落在一个能回答的地方。四格里最后也最容易漏的一格就是这个：后端问出来了、卡片也显示了，
   * 但没有输入框，用户只能去主输入框回答——那会新建一个从零开始的任务（深度研究至今就是这样断的）。
   */
  it('renders the question with an answer box instead of the resume button', () => {
    const wrapper = mount(PptTaskCard, { props: { entry: awaiting('这份 PPT 想讲什么主题？') } })

    expect(wrapper.text()).toContain('这份 PPT 想讲什么主题？')
    expect(wrapper.find('.clarify-form textarea').exists()).toBe(true)
    // 等人的任务点"继续"是空转，不该给这颗按钮
    expect(wrapper.find('.task-actions button').exists()).toBe(false)
  })

  it('treats the question as a prompt for input, not as an error', () => {
    const wrapper = mount(PptTaskCard, { props: { entry: awaiting('想讲什么主题？') } })

    expect(wrapper.find('.error').exists()).toBe(false)
    expect(wrapper.text()).toContain('需要补充信息')
    expect(wrapper.text()).not.toContain('任务正在等待或运行中')
  })

  it('sends the answer to the clarify endpoint and shows the state it resumes into', async () => {
    vi.mocked(pptApi.clarify).mockResolvedValue({
      taskId: 7, status: 'REQUIREMENT', errorMsg: null, outputPath: null, clarifyingQuestion: null
    })
    // 续跑后第一次轮询就到终态，避免用例挂在 1.5 秒的轮询循环里
    vi.mocked(pptApi.status).mockResolvedValue({
      taskId: 7, status: 'SUCCESS', errorMsg: null, outputPath: 'deck.pptx', clarifyingQuestion: null
    })
    const entry = awaiting('想讲什么主题？')
    const wrapper = mount(PptTaskCard, { props: { entry } })

    await wrapper.find('.clarify-form textarea').setValue('讲三季度复盘')
    await wrapper.find('.clarify-form').trigger('submit')

    expect(pptApi.clarify).toHaveBeenCalledWith(7, '讲三季度复盘')
    expect(entry.task.status).toBe('REQUIREMENT')
    // 回答提交后追问区块收起，输入框不再挂在那儿等第二次提交
    expect(wrapper.find('.clarify-form').exists()).toBe(false)
  })

  it('does not submit an empty answer', async () => {
    const wrapper = mount(PptTaskCard, { props: { entry: awaiting('想讲什么主题？') } })

    await wrapper.find('.clarify-form textarea').setValue('   ')
    await wrapper.find('.clarify-form').trigger('submit')

    expect(pptApi.clarify).not.toHaveBeenCalled()
  })
})
