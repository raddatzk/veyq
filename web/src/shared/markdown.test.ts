import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

describe('renderMarkdown', () => {
  it('renders headings, paragraphs and line breaks', () => {
    expect(renderMarkdown('# Title\n\nhello\nworld')).toBe('<h1>Title</h1><p>hello<br>world</p>')
  })

  it('renders bullet and ordered lists', () => {
    expect(renderMarkdown('- a\n- b')).toBe('<ul><li>a</li><li>b</li></ul>')
    expect(renderMarkdown('1. a\n2. b')).toBe('<ol><li>a</li><li>b</li></ol>')
  })

  it('renders inline bold, italic, code and links', () => {
    expect(renderMarkdown('**b** *i* `c`')).toBe('<p><strong>b</strong> <em>i</em> <code>c</code></p>')
    expect(renderMarkdown('[veyq](https://veyq.app)')).toBe('<p><a href="https://veyq.app" rel="noopener noreferrer" target="_blank">veyq</a></p>')
  })

  it('escapes HTML to prevent injection (safe for v-html)', () => {
    expect(renderMarkdown('<script>alert(1)</script>')).toBe('<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>')
    expect(renderMarkdown('a & b')).toBe('<p>a &amp; b</p>')
  })

  it('only allows http(s) links (no javascript: urls)', () => {
    expect(renderMarkdown('[x](javascript:alert(1))')).toBe('<p>[x](javascript:alert(1))</p>')
  })

  it('renders the terms clause shape (## headings + paragraphs)', () => {
    expect(renderMarkdown('# Terms\n\n## 1. Purpose\nBe kind.')).toBe('<h1>Terms</h1><h2>1. Purpose</h2><p>Be kind.</p>')
  })
})
