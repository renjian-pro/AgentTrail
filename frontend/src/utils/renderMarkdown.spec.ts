import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './renderMarkdown'

describe('renderMarkdown', () => {
  it('renders GFM tables', () => {
    const html = renderMarkdown('| a | b |\n|---|---|\n| 1 | 2 |')
    expect(html).toContain('<table>')
    expect(html).toContain('<td>1</td>')
  })

  it('renders bold text as a real element, not literal asterisks', () => {
    const html = renderMarkdown('**加粗**')
    expect(html).toContain('<strong>加粗</strong>')
  })

  it('strips script tags and inline event handlers — model output is untrusted', () => {
    const html = renderMarkdown('<script>alert(1)</script><img src=x onerror="alert(1)">')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('onerror')
  })

  it('forces target=_blank and rel=noopener on links', () => {
    const html = renderMarkdown('[click](https://example.com)')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer"')
  })
})
