import { describe, expect, it } from 'vitest'
import { formatSql, parseSqlResult } from './sqlResult'

describe('sqlResult', () => {
  it('parses a table, metadata and escaped pipes', () => {
    const parsed = parseSqlResult('查询成功，共 2 行（耗时 8ms）。\n\n| name | note |\n|---|---|\n| A | x\\|y |\n| B | z |')
    expect(parsed).toMatchObject({ kind: 'table', totalRows: 2, elapsedMs: 8, rows: [['A', 'x|y'], ['B', 'z']] })
  })

  it('keeps empty, error and unrecognised results distinct', () => {
    expect(parseSqlResult('查询执行成功，但没有匹配数据。').kind).toBe('empty')
    expect(parseSqlResult('查询失败：权限不足').kind).toBe('error')
    expect(parseSqlResult('后端升级中的原始文本').kind).toBe('raw')
  })

  it('formats long SQL around the main clauses', () => {
    expect(formatSql('SELECT * FROM rental WHERE dept_id = 3 ORDER BY rental_id LIMIT 20'))
      .toContain('\nFROM rental')
  })
})
