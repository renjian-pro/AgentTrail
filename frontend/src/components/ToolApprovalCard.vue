<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ApprovalCardState } from '../stores/chat'

const props = defineProps<{ approval: ApprovalCardState }>()
const emit = defineEmits<{ decide: [approved: boolean, rejectionReason?: string] }>()
const rejectionReason = ref('')

const deciding = computed(() => props.approval.status === 'submitting')
const terminal = computed(() => props.approval.status === 'approved' || props.approval.status === 'rejected')
const afterToolExecution = computed(() => props.approval.safePoint === 'AFTER_TOOL_EXECUTION')
const statusText = computed(() => {
  if (props.approval.status === 'submitting') return afterToolExecution.value ? '正在继续生成回答…' : '正在提交审批决定…'
  if (afterToolExecution.value && props.approval.status === 'approved') return '已继续生成回答，工具阶段不会重复处理'
  if (props.approval.status === 'approved') return '已批准，工具执行和回答已继续'
  if (props.approval.status === 'rejected') return '已拒绝，工具未执行，回答已继续'
  return ''
})

function prettyArguments(value: string) {
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}

function decide(approved: boolean) {
  emit('decide', approved, approved ? undefined : (rejectionReason.value.trim() || undefined))
}
</script>

<template>
  <section class="tool-approval-card" aria-live="polite">
    <header>
      <div>
        <strong>{{ afterToolExecution ? '工具阶段已处理，等待继续' : '需要你的确认' }}</strong>
        <p v-if="afterToolExecution">恢复点已记录工具阶段的处理结果；继续操作不会重复批准、拒绝或跳过。</p>
        <p v-else>以下工具可能产生外部影响，确认后才会执行。</p>
      </div>
      <span v-if="!afterToolExecution" class="risk-badge">高风险</span>
    </header>
    <div v-for="tool in approval.pendingTools" :key="tool.toolCallId" class="approval-tool">
      <div class="tool-heading"><b>{{ tool.toolName }}</b><small>{{ tool.riskLevel }}</small></div>
      <pre>{{ prettyArguments(tool.arguments) }}</pre>
    </div>
    <p v-if="statusText" class="approval-status">{{ statusText }}</p>
    <p v-if="approval.status === 'failed'" class="approval-error">恢复失败：{{ approval.error }}</p>
    <template v-if="!terminal">
      <label v-if="!afterToolExecution && approval.status !== 'failed'">
        拒绝原因（可选）
        <textarea v-model="rejectionReason" rows="2" :disabled="deciding" placeholder="例如：金额或收件人不正确" />
      </label>
      <div class="approval-actions">
        <button v-if="afterToolExecution" class="retry-button" type="button" :disabled="deciding"
            @click="decide(true)">继续生成</button>
        <button v-else-if="approval.status === 'failed'" class="retry-button" type="button"
            @click="decide(approval.decision !== 'rejected')">
          重试{{ approval.decision === 'rejected' ? '拒绝' : '批准' }}
        </button>
        <template v-else>
          <button class="reject-button" type="button" :disabled="deciding" @click="decide(false)">拒绝</button>
          <button class="approve-button" type="button" :disabled="deciding" @click="decide(true)">批准执行</button>
        </template>
      </div>
    </template>
  </section>
</template>

<style scoped>
.tool-approval-card { display: grid; gap: 12px; margin-top: 12px; padding: 14px; border: 1px solid #d7b56d; border-radius: 12px; background: color-mix(in srgb, #c9983e 7%, var(--surface)); }
.tool-approval-card header, .tool-heading, .approval-actions { display: flex; align-items: center; gap: 10px; }
.tool-approval-card header { align-items: flex-start; justify-content: space-between; }
.tool-approval-card header p { margin: 4px 0 0; color: var(--muted); font-size: 12px; line-height: 1.5; }
.risk-badge { flex: none; padding: 3px 8px; border-radius: 999px; color: #8c551d; background: #f5ddb2; font-size: 11px; font-weight: 700; }
.approval-tool { display: grid; gap: 6px; }
.tool-heading small { margin-left: auto; color: var(--faint); font-size: 10px; }
pre { max-height: 180px; margin: 0; padding: 9px; overflow: auto; border-radius: 8px; color: var(--text); background: var(--canvas); font: 11px/1.55 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; }
label { display: grid; gap: 6px; color: var(--muted); font-size: 11px; }
textarea { width: 100%; padding: 8px; resize: vertical; border: 1px solid var(--line-strong); border-radius: 8px; color: var(--text); background: var(--surface); font: inherit; }
.approval-actions { justify-content: flex-end; }
.approval-actions button { padding: 7px 12px; border: 1px solid var(--line-strong); border-radius: 8px; color: var(--text); background: var(--surface); }
.approval-actions button:disabled { opacity: .55; }
.approve-button, .retry-button { color: #fff !important; border-color: #8c551d !important; background: #8c551d !important; }
.approval-status { margin: 0; color: var(--muted); font-size: 12px; }
.approval-error { margin: 0; color: #b34e4e; font-size: 12px; }
</style>
