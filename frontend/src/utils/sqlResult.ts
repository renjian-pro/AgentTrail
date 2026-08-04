export type SqlToolResult =
  | { kind: 'table'; headers: string[]; rows: string[][]; totalRows: number | null; truncated: boolean; elapsedMs: number | null; note: string }
  | { kind: 'empty'; guidance: string }
  | { kind: 'error'; message: string }
  | { kind: 'raw'; text: string }

export function parseSqlResult(result: string): SqlToolResult {
  const text = result.trim()
  if (!text) return { kind: 'raw', text: '' }

  const lines = text.split(/\r?\n/).map(line => line.trim()).filter(Boolean)
  const start = lines.findIndex(line => line.startsWith('|') && line.endsWith('|'))
  if (start >= 0 && start + 1 < lines.length && isSeparator(lines[start + 1])) {
    const headers = splitRow(lines[start])
    const rows: string[][] = []
    for (const line of lines.slice(start + 2)) {
      if (!line.startsWith('|') || !line.endsWith('|')) break
      const row = splitRow(line)
      if (row.length !== headers.length) return { kind: 'raw', text }
      rows.push(row)
    }
    return {
      kind: 'table',
      headers,
      rows,
      totalRows: matchNumber(text, /共\s*(\d+)\s*行/),
      truncated: /超过|截断|仅展示|只展示/.test(text),
      elapsedMs: matchNumber(text, /耗时\s*(\d+)\s*ms/i),
      note: lines.slice(start + 2 + rows.length).join(' ')
    }
  }

  if (/查询失败|校验未通过|Error\s*:/i.test(text)) {
    return { kind: 'error', message: text }
  }
  if (/没有匹配|空结果|没有任何数据|共\s*0\s*行/.test(text)) {
    return { kind: 'empty', guidance: text }
  }
  return { kind: 'raw', text }
}

export function formatSql(sql: string): string {
  return sql.replace(/\s+/g, ' ').trim()
    .replace(/\s+(FROM|WHERE|GROUP BY|ORDER BY|HAVING|LIMIT|UNION(?: ALL)?|(?:LEFT |RIGHT |INNER |FULL |CROSS )?JOIN)\s+/gi, '\n$1 ')
}

function splitRow(line: string): string[] {
  const body = line.slice(1, -1)
  const cells: string[] = []
  let current = ''
  let escaped = false
  for (const char of body) {
    if (escaped) {
      current += char
      escaped = false
    } else if (char === '\\') {
      escaped = true
    } else if (char === '|') {
      cells.push(current.trim())
      current = ''
    } else {
      current += char
    }
  }
  if (escaped) current += '\\'
  cells.push(current.trim())
  return cells
}

function isSeparator(line: string): boolean {
  return splitRow(line).every(cell => /^:?-{3,}:?$/.test(cell))
}

function matchNumber(text: string, pattern: RegExp): number | null {
  const value = text.match(pattern)?.[1]
  return value == null ? null : Number(value)
}
