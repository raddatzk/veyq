// `veyq-admin/controls-through-the-layer` — the rule of the block of that name (eslint.config.js): a form control is
// a layer component. A native `<select>`, `<textarea>` or text-like `<input>` written in a view draws the browser's own
// field, or a sheet's private copy of ours, and drifts from the one field box `shared/ui/layer.css` gives every field.
// Outside `src/shared/ui/` a template therefore names `VeyqInput`, `VeyqPassword`, `VeyqTextarea`, `VeyqSelect`,
// `VeyqCombobox` or `VeyqCheckbox`. The same rule as web's `src/web/eslint/formControls.js`, kept as this package's own
// copy as `featureMocks.js` is: the workspace shares `@veyq/crypto` and nothing else (ADR 0065).
//
// An `<input>` stays allowed only with a static `type` the layer has no field for: `file` (a hidden picker a button
// opens), `checkbox` and `radio` (a tile's own selection mark), `range` and `hidden`. No `type`, any other one, or a
// bound `:type` (which no reader can check) is refused. A rule rather than `vue/no-restricted-syntax` selectors,
// because a block that later sets that rule for the same files would replace it (flat config keeps the last one's).
// LIMIT: it reads templates of `.vue` files only — a render function (`h('select')`) or a test's inline stub template
// (a string in a `.ts` file) is not seen; and a layer component given a `type` (`<VeyqInput type="datetime-local">`)
// is the layer's to answer, not this rule's.

/** The `<input>` types a template may still write by hand. */
export const NATIVE_INPUT_TYPES = new Set(['file', 'checkbox', 'radio', 'range', 'hidden'])

function typeOf(element) {
  for (const attribute of element.startTag.attributes) {
    if (!attribute.directive && attribute.key.name === 'type') return { bound: false, value: attribute.value?.value?.toLowerCase() ?? '' }
    if (attribute.directive && attribute.key.name.name === 'bind' && attribute.key.argument?.name === 'type') return { bound: true }
  }
  return undefined
}

const rule = {
  meta: {
    type: 'problem',
    docs: { description: 'A form control is a layer component: no native <select>, <textarea> or text-like <input> outside src/shared/ui/.' },
    schema: [],
    messages: {
      select: 'A drop-down is VeyqSelect (src/shared/ui/), never a native <select>.',
      textarea: 'A text field over several lines is VeyqTextarea (src/shared/ui/), never a native <textarea>.',
      input:
        'A field is a layer component (VeyqInput, VeyqPassword, VeyqCombobox from src/shared/ui/); a native <input> keeps only type="file" | "checkbox" | "radio" | "range" | "hidden".',
      boundType: 'A native <input> with a bound :type cannot be checked: write its type out (file, checkbox, radio, range, hidden) or use a layer field.',
    },
  },
  create(context) {
    const services = context.sourceCode.parserServices
    if (!services?.defineTemplateBodyVisitor) return {}
    return services.defineTemplateBodyVisitor({
      VElement(element) {
        if (element.namespace !== 'http://www.w3.org/1999/xhtml') return
        const name = element.rawName
        if (name === 'select' || name === 'textarea') {
          context.report({ node: element.startTag, messageId: name })
          return
        }
        if (name !== 'input') return
        const type = typeOf(element)
        if (type?.bound) context.report({ node: element.startTag, messageId: 'boundType' })
        else if (!type || !NATIVE_INPUT_TYPES.has(type.value)) context.report({ node: element.startTag, messageId: 'input' })
      },
    })
  },
}

export const plugin = { meta: { name: 'veyq-admin' }, rules: { 'controls-through-the-layer': rule } }
