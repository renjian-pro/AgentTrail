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
})
