import { createRouter, createWebHistory } from 'vue-router'
import ChatView from './views/ChatView.vue'

/** 对话/文件问答/联网搜索/Deep Research/PPT 生成共用一个入口，不再分页面路由。 */
export default createRouter({ history: createWebHistory(), routes: [
  { path: '/', redirect: '/chat' },
  { path: '/chat', component: ChatView }
] })
