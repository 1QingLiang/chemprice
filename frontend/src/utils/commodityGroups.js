// 商品大类分组工具（commodity.category 为一级大类，如 化工/塑料/能源/化肥…）
const UNKNOWN = '未分类'

/** 商品列表按大类分组的顺序数组：{ category, items } */
export function groupByCategory(list = []) {
  const map = {}
  const order = []
  for (const c of list) {
    const cat = (c && c.category) ? String(c.category) : UNKNOWN
    if (!map[cat]) { map[cat] = []; order.push(cat) }
    map[cat].push(c)
  }
  // 中文大类按 localeCompare，未分类放最后
  order.sort((a, b) => {
    if (a === UNKNOWN) return 1
    if (b === UNKNOWN) return -1
    return a.localeCompare(b, 'zh-Hans-CN')
  })
  return order.map(cat => ({ category: cat, items: map[cat] }))
}

/** 去重后的大类名数组 */
export function categoryNames(list = []) {
  return groupByCategory(list).map(g => g.category)
}

/** 取某商品的大类（无则未分类） */
export function categoryOf(c) {
  return (c && c.category) ? String(c.category) : UNKNOWN
}
