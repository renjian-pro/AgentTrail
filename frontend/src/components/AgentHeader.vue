<script setup lang="ts">
import { AGENT_KIND_LABELS as LABELS, AGENT_KIND_MARKS as MARKS, type AgentKind } from '../stores/chat'

/**
 * 会话头部的能力模式选择器 + 常驻标识（issue #92，2026-08-17 按 §7.2 改为互斥模式）。
 *
 * <p>四个模式互斥，同一时刻只能选一个，**随时可切、不锁定**。选中态本身就是那个常驻标识——
 * 不需要再额外写一句"本次会话：XX"，位置和高亮已经说清楚了。
 *
 * <p>为什么常驻而不是像最早那样只在选中时提示一行"下一条消息将使用 XX"：一次性提示消失之后
 * 用户就不知道自己在哪个模式下了，而模式发完还会静默复位——两件事叠起来就是"用户以为在
 * 数据分析、模型却在编数据"那条静默失败链路。
 *
 * <p><b>锁定语义已删除</b>：曾经首条消息发出后就锁死本会话的 Agent，出口是「换一个」+
 * 「带着这段对话新开」。那套约束建立在"换执行器会让历史里的 tool_calls 指向当前不存在的
 * 工具"之上，而核对实现后该故障并不存在——跨轮历史只回放 question/answer，从不带 tool_calls
 * （`JdbcSessionStore.loadHistory`，见 requirements.md §7.3）。约束没了，出口也就不需要了。
 *
 * <p>点已选中的模式＝取消，回到普通对话；普通对话本身也可以直接点。两条路都通，因为
 * "取消"和"切到普通对话"在这个模型里就是同一件事。
 */
const props = defineProps<{ agentKind: AgentKind }>()
const emit = defineEmits<{ change: [AgentKind] }>()

/**
 * 直接由共享的标签表派生，不再手抄一份列表——`Record<AgentKind, string>` 受编译器约束，
 * 少写一个模式会报错；而一个平铺数组不会，加了模式却漏进这里只会让它静默不渲染，
 * 正是 store 里那段注释讲的失效方式。键序即视觉顺序：普通对话打头（默认态），
 * 其余按"换执行器 → 异步任务"排。
 */
const KINDS = Object.keys(LABELS) as AgentKind[]

/** 再点一次当前模式＝取消选择，落回普通对话。普通对话自己没有"取消"，点了就是保持。 */
function pick(kind: AgentKind) {
  emit('change', kind === props.agentKind ? 'chat' : kind)
}
</script>

<template>
  <header class="agent-header">
    <button v-for="kind in KINDS" :key="kind" type="button"
        class="agent-option" :class="{ active: kind === agentKind }"
        :aria-pressed="kind === agentKind" @click="pick(kind)">
      <i>{{ MARKS[kind] }}</i>{{ LABELS[kind] }}
    </button>
  </header>
</template>
