// `.visually-hidden` (style.css) hides an element from sight but not from assistive technology, and must win over every
// component rule: `.veyq-form input:not(...)` (styles/ui.css, `width: 100%`) out-ranks a bare class, and the Secret Key
// field's hidden file input then took the form's width and widened the login page past a 390 px screen to 422 px
// (measured in headless Chrome, 2026-11-08). CSS a unit test cannot render, so the rule's box is read from source.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('./style.css', import.meta.url), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
const body = /(?:^|\})\s*\.visually-hidden\s*\{([^}]*)\}/.exec(css)?.[1] ?? ''
const decl = (prop: string) => new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`).exec(body)?.[1].trim()

describe('.visually-hidden', () => {
  it.each([
    ['position', 'absolute !important'],
    ['width', '1px !important'],
    ['height', '1px !important'],
    ['min-width', '0 !important'],
    ['margin', '-1px !important'],
  ])('holds its %s against any component rule', (prop, value) => {
    expect(decl(prop)).toBe(value)
  })
})
