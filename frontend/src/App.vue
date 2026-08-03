<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { useChatStore } from './stores/chat'
import UserBadge from './components/UserBadge.vue'

const dark = ref(false)
const router = useRouter()
const chat = useChatStore()
const title = computed(() => chat.sessions.find(session => session.id === chat.conversationId)?.title ?? 'New conversation')

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
      <div class="sidebar-head"><RouterLink class="brand" to="/chat"><span class="brand-mark">A</span><span>AgentTrail</span></RouterLink><button class="sidebar-action" aria-label="Toggle theme" @click="dark = !dark">{{ dark ? '☀' : '◐' }}</button></div>
      <button class="new-chat" @click="createChat"><span>＋</span> New chat</button>
      <nav class="primary-nav" aria-label="Primary navigation"><RouterLink class="nav-item active" to="/chat"><span>✓</span> Assistant</RouterLink><RouterLink class="nav-item" to="/roles"><span>◈</span> Roles</RouterLink><RouterLink class="nav-item" to="/admin/users"><span>◎</span> Users</RouterLink></nav>
      <div class="history"><small>Recent conversations</small><button v-for="session in chat.sessions" :key="session.id" :class="{ selected: session.id === chat.conversationId }" @click="openChat(session.id)">{{ session.title }}</button><span v-if="!chat.sessions.length" class="empty-history">No conversations yet</span><button v-if="chat.sessionsHasMore" class="load-more" @click="chat.loadMoreSessions">Load more</button></div>
      <div class="sidebar-bottom"><UserBadge /></div>
    </aside>
    <section class="page"><header><span>{{ title }}</span><span class="status-pill"><i /> Connected</span></header><RouterView /></section>
  </main>
</template>
