import { describe, expect, it } from 'vitest'
import { looksLikeDataQuestion } from './dataQuestionHint'

describe('looksLikeDataQuestion', () => {
  it.each([
    '上个月的订单量是多少',
    '各门店的营收排名是怎样的',
    '一共有多少条租赁记录',
    '帮我查一下这个季度的销售额',
    '客户数量环比增长了多少'
  ])('flags %s as needing real data', message => {
    expect(looksLikeDataQuestion(message)).toBe(true)
  })

  /**
   * 反例是这个函数的主要风险面。业务名词是必要条件，正是为了挡住下面这几类——
   * 只要"统计""查询"这种词一出现就提示，用户在聊技术话题时会被反复打断。
   */
  it.each([
    ['统计学是什么', '有动作词但没有业务名词'],
    ['帮我写一个 SQL 查询的教程', '在聊技术，不是在问我们的数据'],
    ['客户是什么意思', '有业务名词但没有动作或疑问量词'],
    ['今天天气怎么样', '完全无关'],
    ['多少钱能买到一台笔记本', '有疑问量词但没有业务名词'],
    ['', '空串'],
  ])('leaves %s alone (%s)', message => {
    expect(looksLikeDataQuestion(message)).toBe(false)
  })

  it('treats null and undefined as not a data question', () => {
    expect(looksLikeDataQuestion(null)).toBe(false)
    expect(looksLikeDataQuestion(undefined)).toBe(false)
  })
})
