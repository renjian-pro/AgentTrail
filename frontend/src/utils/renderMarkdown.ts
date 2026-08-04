import { marked } from 'marked'
import DOMPurify from 'dompurify'

// marked supports GFM (tables, strikethrough, autolinks) by default since v4 — no extra plugin needed,
// unlike remark where GFM is opt-in via remark-gfm.
marked.setOptions({ gfm: true, breaks: true })

// Model output is untrusted text rendered as HTML via v-html — every external link must open in a
// new tab without leaking window.opener, and every attribute DOMPurify doesn't already strip must be
// assumed hostile. This hook runs on every node DOMPurify keeps, mirroring the `target="_blank"
// rel="noopener noreferrer"` link override applied in the reference chat UI's markdown renderer.
DOMPurify.addHook('afterSanitizeAttributes', node => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

/** Render Markdown to sanitized HTML, safe to pass to v-html. */
export function renderMarkdown(source: string): string {
  const html = marked.parse(source, { async: false })
  return DOMPurify.sanitize(html)
}
