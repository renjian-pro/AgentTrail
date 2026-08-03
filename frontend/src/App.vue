<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { useChatStore } from './stores/chat'

const dark = ref(false)
const router = useRouter()
const chat = useChatStore()
/** 对话/Deep Research/PPT 不再是分开的页面，标题就是当前会话本身。 */
const title = computed(() => chat.sessions.find(session => session.id === chat.conversationId)?.title ?? '新对话')

onMounted(() => void chat.hydrateSessions())

function createChat() {
  chat.startNewConversation()
  void router.push('/chat')
}

function openChat(id: string) {
  void router.push('/chat')
  void chat.openSession(id)
}
</script>

<template>
  <main :class="{ dark }" class="shell">
    <aside class="sidebar">
      <div class="sidebar-head">
        <RouterLink class="brand" to="/chat"><span class="brand-mark">A</span><span>AgentTrail</span></RouterLink>
        <button class="sidebar-action" aria-label="切换主题" @click="dark = !dark">{{ dark ? '☀' : '◐' }}</button>
      </div>
      <button class="new-chat" @click="createChat"><span>＋</span> 新对话</button>
      <nav class="primary-nav" aria-label="能力导航">
        <span class="nav-item active"><span>✦</span> 智能助理</span>
      </nav>
      <div class="history">
        <small>最近对话</small>
        <button v-for="session in chat.sessions" :key="session.id" :class="{ selected: session.id === chat.conversationId }" @click="openChat(session.id)">
          {{ session.title }}
        </button>
        <span v-if="!chat.sessions.length" class="empty-history">还没有历史对话</span>
        <button v-if="chat.sessionsHasMore" class="load-more" @click="chat.loadMoreSessions">加载更多</button>
      </div>
      <div class="sidebar-bottom">
        <span class="user-avatar">A</span>
        <span><b>anonymous</b><small>本地工作区</small></span>
      </div>
    </aside>
    <section class="page">
      <header><span>{{ title }}</span><span class="status-pill"><i /> 已连接</span></header>
      <RouterView />
    </section>
  </main>
</template>
