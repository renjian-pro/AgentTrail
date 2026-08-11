import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ResearchReportCard from './ResearchReportCard.vue'

describe('ResearchReportCard', () => {
  it('highlights the current backend progress step', () => {
    const wrapper = mount(ResearchReportCard, {
      props: {
        entry: { kind: 'research', question: '测试', currentStep: 'SEARCHING' }
      }
    })

    const steps = wrapper.findAll('.research-flow span')
    expect(steps[0].classes()).not.toContain('active')
    expect(steps[1].classes()).toContain('active')
    expect(steps[2].classes()).not.toContain('active')
    expect(steps[3].classes()).not.toContain('active')
  })

  it('renders report markdown as readable sections and lists', () => {
    const wrapper = mount(ResearchReportCard, {
      props: {
        entry: {
          kind: 'research',
          question: '研究 Agent 工程师市场',
          result: {
            needsClarification: false,
            clarifyingQuestion: null,
            researchTopic: 'Agent 工程师市场',
            taskResults: [],
            report: '## 摘要\n岗位需求持续增长。\n\n### 核心发现\n- 薪资上升\n- 技能要求更加综合'
          }
        }
      }
    })

    expect(wrapper.findAll('.report-body h3').map(node => node.text())).toEqual(['摘要'])
    expect(wrapper.findAll('.report-body h4').map(node => node.text())).toEqual(['核心发现'])
    expect(wrapper.findAll('.report-body li').map(node => node.text())).toEqual(['薪资上升', '技能要求更加综合'])
    expect(wrapper.text()).not.toContain('##')
  })
})
