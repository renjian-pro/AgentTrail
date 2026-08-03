import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AttachedFileList from './AttachedFileList.vue'
import CollapsibleChip from './CollapsibleChip.vue'
import FileUploadWidget from './FileUploadWidget.vue'
import PptTaskCard from './PptTaskCard.vue'
import TodoProgressBar from './TodoProgressBar.vue'

describe('chat detail components', () => {
  it('expands thought and tool details only after a click', async () => {
    const thought = mount(CollapsibleChip, { props: { label: 'Thought', content: '推理内容' } })
    const tool = mount(CollapsibleChip, { props: { label: 'web_search', content: '{"q":"AI"}' } })
    expect(thought.find('pre').exists()).toBe(false)
    expect(tool.find('pre').exists()).toBe(false)
    await thought.find('button').trigger('click')
    await tool.find('button').trigger('click')
    expect(thought.text()).toContain('推理内容')
    expect(tool.text()).toContain('web_search')
  })

  it('renders todo progress and emits the selected file and attachment removal', async () => {
    const todo = mount(TodoProgressBar, { props: { items: [{ content: '检索资料', status: 'IN_PROGRESS' }] } })
    const upload = mount(FileUploadWidget)
    const attached = mount(AttachedFileList, { props: {
      files: [{ fileId: 7, fileName: 'brief.pdf', kind: 'TEXT', sizeBytes: 8, parsedTextLength: 3, routedToRag: false }],
      busy: false,
      error: ''
    } })
    const file = new File(['test'], 'brief.pdf', { type: 'application/pdf' })
    const input = upload.find('input')
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await attached.find('button').trigger('click')
    expect(todo.text()).toContain('检索资料')
    expect(upload.emitted('upload')?.[0]).toEqual([file])
    expect(attached.emitted('remove')).toEqual([[7]])
  })

  it('uses the controlled download endpoint for completed legacy PPT tasks', () => {
    const card = mount(PptTaskCard, { props: { entry: {
      kind: 'ppt',
      prompt: 'Create a quarterly summary',
      task: { taskId: 9, status: 'SUCCESS', errorMsg: null, outputPath: 'E:/internal/ppt/9.pptx' }
    } } })

    expect(card.find('a').attributes('href')).toBe('/agent/v1/ppt/9/download')
  })
})
