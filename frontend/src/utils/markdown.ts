export type ReportBlock =
  | { kind: 'heading'; level: 3 | 4; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'list'; items: string[] }

function cleanInlineMarkdown(value: string) {
  return value.replace(/\*\*(.*?)\*\*/g, '$1').replace(/`([^`]+)`/g, '$1').trim()
}

/** Render the small Markdown subset produced by DeepResearch without v-html. */
export function parseReport(markdown: string): ReportBlock[] {
  const blocks: ReportBlock[] = []
  let paragraph: string[] = []
  let list: string[] = []
  const flushParagraph = () => {
    if (paragraph.length) blocks.push({ kind: 'paragraph', text: cleanInlineMarkdown(paragraph.join(' ')) })
    paragraph = []
  }
  const flushList = () => {
    if (list.length) blocks.push({ kind: 'list', items: list })
    list = []
  }

  for (const rawLine of markdown.split(/\r?\n/)) {
    const line = rawLine.trim()
    const heading = /^(#{1,4})\s+(.+)$/.exec(line)
    const listItem = /^(?:[-*]|\d+[.)])\s+(.+)$/.exec(line)
    if (!line) {
      flushParagraph()
      flushList()
    } else if (heading) {
      flushParagraph()
      flushList()
      blocks.push({ kind: 'heading', level: heading[1].length <= 2 ? 3 : 4, text: cleanInlineMarkdown(heading[2]) })
    } else if (listItem) {
      flushParagraph()
      list.push(cleanInlineMarkdown(listItem[1]))
    } else {
      flushList()
      paragraph.push(line)
    }
  }
  flushParagraph()
  flushList()
  return blocks
}
