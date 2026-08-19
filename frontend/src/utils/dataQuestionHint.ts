/**
 * 判断一句话像不像"要查真实业务数据"的问题（issue #94）。
 *
 * <p>**用关键词，不调 LLM。** 这是 PPT 状态感知消息路由和 DeepResearch 需求澄清已经踩过并定案的
 * 同一个坑（踩坑点 #52）：判定成本低、行为可预测、说得清依据。多一次 LLM 调用换来的语义理解，
 * 在"要不要显示一张提示卡片"这个场景不值得——判错的代价是一张多余的卡片，不是错误的答案。
 *
 * <p>判定规则是**业务名词是必要条件**，光有动作词或疑问词不算：
 *
 * <ul>
 *   <li>「统计学是什么」——有"统计"但没有业务名词，不命中（否则任何提到统计/查询的话题都会误报）
 *   <li>「客户是什么意思」——有"客户"但没有动作或疑问量词，不命中
 *   <li>「上个月的订单量是多少」——"订单" + "多少"，命中
 * </ul>
 */

/** 明确的数据动作。 */
const ACTIONS = ['查一下', '查查', '查询', '统计', '汇总', '排名', '排行', '占比', '增长率', '环比', '同比', '明细'];

/** 期待一个具体数值/条目的疑问量词。 */
const QUANTIFIERS = ['多少', '几个', '几笔', '几条', '最多', '最少', '最高', '最低', '前十', '前五', '总共', '一共'];

/** 业务名词——必要条件。命中这些才说明问的是"我们的数据"，而不是一个通用话题。 */
const DOMAIN = [
  '订单', '销量', '销售额', '营收', '收入', '利润', '客户', '用户数', '会员',
  '租赁', '门店', '店铺', '库存', '付款', '支付', '交易', '业绩', '数据库', '报表'
];

const hits = (message: string, words: string[]) => words.some(word => message.includes(word));

/**
 * @returns true 表示这句话看起来需要查真实数据，当前会话（普通对话）没有数据库工具，
 *          应该引导用户开一个数据分析会话
 */
export function looksLikeDataQuestion(message: string | undefined | null): boolean {
  if (!message) return false;
  const text = message.trim();
  if (!text) return false;
  return hits(text, DOMAIN) && (hits(text, ACTIONS) || hits(text, QUANTIFIERS));
}
