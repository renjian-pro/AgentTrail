import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MessageInput from './MessageInput.vue'

describe('MessageInput', () => {
  it('emits a non-empty message when submitted', async () => {
    const wrapper = mount(MessageInput, { props: { busy: false } })
    await wrapper.find('textarea').setValue('帮我总结这份文档')
    await wrapper.find('form').trigger('submit')
    expect(wrapper.emitted('send')).toEqual([['帮我总结这份文档']])
  })

  // 同一颗按钮承担发送和停止，所以"点它会发生什么"必须由状态唯一决定。
  it('turns the send button into the stop control while the run can be aborted', async () => {
    const wrapper = mount(MessageInput, { props: { busy: true, canStop: true } })
    const button = wrapper.find('button')
    expect(button.attributes('aria-label')).toBe('停止生成')
    expect(button.attributes('disabled')).toBeUndefined()
    await button.trigger('click')
    expect(wrapper.emitted('stop')).toHaveLength(1)
    expect(wrapper.emitted('send')).toBeUndefined()
  })

  it('stays a disabled busy indicator when the run cannot be aborted', () => {
    const button = mount(MessageInput, { props: { busy: true, canStop: false } }).find('button')
    expect(button.attributes('aria-label')).toBe('正在生成')
    expect(button.attributes('disabled')).toBeDefined()
  })
})
