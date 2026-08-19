import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PptTaskCard from './PptTaskCard.vue'
import { TOKEN_KEY } from '../api/auth-token'

/**
 * 这个文件钉住的是一条**只在真实浏览器里才暴露**的失败：下载曾经是 `<a href>` + target=_blank，
 * 单元测试里它只是一个 DOM 属性、看不出任何问题，实际点下去却因为浏览器导航带不上
 * Authorization 请求头而稳定拿到 401 AUTH_REQUIRED，还是在另一个标签页里报的错。
 *
 * <p>所以断言的形态是"点击后发起了一个带鉴权头的 fetch"，而不是"渲染出了正确的 href"——
 * 后者正是当初通过了却没能拦住这个 bug 的那种断言。
 */
describe('PptTaskCard 下载', () => {
  const successTask = { taskId: 7, status: 'SUCCESS', errorMsg: null, outputPath: '/tmp/a.pptx' }

  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem(TOKEN_KEY, 'test-token')
    vi.restoreAllMocks()
    // jsdom 没实现这两个，saveBlob 会直接用到
    URL.createObjectURL = vi.fn(() => 'blob:fake')
    URL.revokeObjectURL = vi.fn()
  })

  it('带着 Authorization 请求头取文件，而不是让浏览器导航过去', async () => {
    const fetchMock = vi.fn(async () => new Response(new Blob(['fake-pptx']), {
      status: 200,
      headers: { 'Content-Disposition': "attachment; filename*=UTF-8''%E9%94%80%E5%94%AE%E6%B1%87%E6%8A%A5.pptx" }
    }))
    vi.stubGlobal('fetch', fetchMock)
    const clicked: string[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(function (this: HTMLAnchorElement) { clicked.push(this.download) })

    const wrapper = mount(PptTaskCard, { props: { entry: { kind: 'ppt', prompt: '销售汇报', task: successTask } } })
    // 不能再有裸链接——它是这个 bug 的形态本身
    expect(wrapper.find('a').exists()).toBe(false)
    await wrapper.find('.task-actions button').trigger('click')
    await flushPromises()

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/agent/v1/ppt/7/download')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer test-token')
    // token 不能进 URL：那会让凭证进浏览器历史、访问日志和 Referer
    expect(url).not.toContain('test-token')
    // 文件名用服务端 Content-Disposition 里那个（RFC 5987 编码的中文名）
    expect(clicked).toEqual(['销售汇报.pptx'])
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:fake')
  })

  it('下载失败时把错误显示在卡片上，而不是丢进另一个标签页', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ message: 'PPT 文件不存在: 7' }), { status: 404 })))
    const entry = { kind: 'ppt' as const, prompt: '销售汇报', task: successTask }
    const wrapper = mount(PptTaskCard, { props: { entry } })

    await wrapper.find('.task-actions button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.error').text()).toContain('PPT 文件不存在')
  })

  it('用可折叠过程展示整体阶段和图片明细，不再渲染追问或修改输入框', () => {
    const task = {
      taskId: 8, status: 'AWAITING_INPUT', errorMsg: null, outputPath: null,
      clarifyingQuestion: '这份 PPT 主要想讲什么主题？',
      taskView: {
        taskId: 8, conversationId: 'conv-8', operation: 'CREATE', pipelineState: 'AWAITING_INPUT',
        runStatus: 'WAITING_INPUT', revision: 4, currentStageLabel: '等待补充主题',
        completedStages: ['INIT'], progressPercent: 9,
        progressEvents: [
          { sequence: 1, stage: 'INIT', level: 'STAGE', status: 'COMPLETED', message: '初始化任务完成', current: null, total: null, occurredAtMillis: 1 },
          { sequence: 2, stage: 'IMAGE', level: 'DETAIL', status: 'COMPLETED', message: '图片生成完成（3/6）', current: 3, total: 6, occurredAtMillis: 2 }
        ],
        clarification: '这份 PPT 主要想讲什么主题？', failure: null, warnings: [], artifact: null,
        baseTaskId: null, baseArtifactId: null, createdAtMillis: 1, updatedAtMillis: 2,
        capabilities: { canCancel: false, canResume: false, canAnswer: true, canDownload: false, canModify: false }
      }
    }
    const wrapper = mount(PptTaskCard, { props: { entry: { kind: 'ppt', prompt: '做一份 PPT', task } } })

    expect(wrapper.get('.ppt-timeline').text()).toContain('初始化任务完成')
    expect(wrapper.get('.ppt-timeline').text()).toContain('图片生成完成（3/6）')
    expect(wrapper.get('.ppt-conversation-hint').text()).toContain('下方会话输入框回复')
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.text()).toContain('下方会话输入框')
  })
})
