<script setup lang="ts">
import { ref } from 'vue'
import type { DeptTreeNode } from '../api/sys-api'

defineProps<{ nodes: DeptTreeNode[]; modelValue: number[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: number[]] }>()
const expanded = ref<Set<number>>(new Set())

function isExpanded(id: number) { return expanded.value.has(id) }
function toggleExpanded(id: number) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  expanded.value = next
}
function toggleSelected(id: number, checked: boolean, current: number[]) {
  const next = new Set(current)
  if (checked) next.add(id); else next.delete(id)
  emit('update:modelValue', [...next])
}
</script>

<template>
  <div class="dept-tree">
    <template v-for="node in nodes" :key="node.id">
      <div class="dept-row">
        <button v-if="node.children?.length" class="tree-toggle" type="button" @click="toggleExpanded(node.id)">{{ isExpanded(node.id) ? '−' : '+' }}</button><span v-else class="tree-spacer" />
        <label><input type="checkbox" :checked="modelValue.includes(node.id)" @change="toggleSelected(node.id, ($event.target as HTMLInputElement).checked, modelValue)" /> {{ node.name }}</label>
      </div>
      <div v-if="node.children?.length && isExpanded(node.id)" class="dept-children"><DeptTree :nodes="node.children" :model-value="modelValue" @update:model-value="emit('update:modelValue', $event)" /></div>
    </template>
  </div>
</template>
