import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it } from 'vitest'
import App from './App.vue'
import { useChatStore } from './stores/chat'

describe('App sidebar', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('makes the new-chat button reset the active conversation even on /chat', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useChatStore()
    store.acceptConversation('existing', '已有对话')
    store.messages.push({ kind: 'chat', role: 'user', content: '旧消息' })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/chat', component: { template: '<div />' } }]
    })
    await router.push('/chat')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [pinia, router] } })
    await flushPromises()
    await wrapper.get('.new-chat').trigger('click')
    expect(store.messages).toEqual([])
    expect(store.conversationId).toBeUndefined()
  })
})
