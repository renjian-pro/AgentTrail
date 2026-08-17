import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ToolApprovalCard from './ToolApprovalCard.vue'

const approval = {
  conversationId: 'c1',
  reason: 'HITL_APPROVAL',
  pausedAtMillis: 1,
  pendingTools: [{
    toolCallId: 't1', toolName: 'chargeCard',
    arguments: '{"amount":100,"api_token":"***"}', riskLevel: 'HIGH_RISK'
  }],
  status: 'pending' as const
}

describe('ToolApprovalCard', () => {
  it('shows risk and sanitized arguments, then emits an approval decision', async () => {
    const wrapper = mount(ToolApprovalCard, { props: { approval } })

    expect(wrapper.text()).toContain('chargeCard')
    expect(wrapper.text()).toContain('高风险')
    expect(wrapper.text()).toContain('***')
    await wrapper.get('.approve-button').trigger('click')

    expect(wrapper.emitted('decide')).toEqual([[true, undefined]])
  })

  it('passes an optional rejection reason and disables decisions while submitting', async () => {
    const wrapper = mount(ToolApprovalCard, { props: { approval } })
    await wrapper.get('textarea').setValue('金额异常')
    await wrapper.get('.reject-button').trigger('click')
    expect(wrapper.emitted('decide')).toEqual([[false, '金额异常']])

    await wrapper.setProps({ approval: { ...approval, status: 'submitting' as const, decision: 'approved' as const } })
    expect(wrapper.findAll('button').every(button => button.attributes('disabled') !== undefined)).toBe(true)
  })

  it('offers the same decision again after a failed resume', async () => {
    const wrapper = mount(ToolApprovalCard, {
      props: { approval: { ...approval, status: 'failed', decision: 'approved', error: 'provider unavailable' } }
    })

    expect(wrapper.text()).toContain('provider unavailable')
    await wrapper.get('.retry-button').trigger('click')
    expect(wrapper.emitted('decide')).toEqual([[true, undefined]])
  })
})
