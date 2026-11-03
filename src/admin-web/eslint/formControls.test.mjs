// The console's copy of web's bite (src/web/eslint/formControls.test.mjs). The templates
// `veyq-admin/controls-through-the-layer` must refuse — a native select, textarea and every text-like input,
// typed, untyped or with a type no reader can check — and the ones it must leave alone: the hidden file picker, a
// tile's checkbox, the layer's own components. Which files the rule applies to is eslint.config.js' block (bitten live
// against the real config, not here).
import { RuleTester } from 'eslint'
import vueParser from 'vue-eslint-parser'
import { describe, it } from 'vitest'

import { plugin } from './formControls.js'

RuleTester.describe = describe
RuleTester.it = it
RuleTester.itOnly = it.only

const tester = new RuleTester({ languageOptions: { parser: vueParser, ecmaVersion: 'latest', sourceType: 'module' } })
const FILE = '/repo/admin-web/src/features/instance/Thing.vue'
const template = (body) => ({ filename: FILE, code: `<template><form>${body}</form></template>` })
const refused = (body, messageId) => ({ ...template(body), errors: [{ messageId }] })

tester.run('controls-through-the-layer', plugin.rules['controls-through-the-layer'], {
  valid: [
    template('<input ref="picker" type="file" accept="image/*" hidden />'),
    template('<input type="FILE" />'),
    template('<input type="checkbox" :checked="on" />'),
    template('<input type="radio" name="pick" value="a" />'),
    template('<input type="range" min="0" max="10" />'),
    template('<input type="hidden" name="token" />'),
    template('<VeyqInput id="name" v-model="name" />'),
    template('<VeyqTextarea v-model="text" rows="4" />'),
    template('<VeyqSelect v-model="pick" :options="options" />'),
    template('<VeyqInput v-model="at" type="datetime-local" />'), // a layer field given a type is the layer's to answer
    template('<option value="a">A</option>'), // an option outside a select is no control of its own
    { filename: FILE, code: '<script setup lang="ts">const html = "<select></select>"</script><template><p /></template>' },
  ],
  invalid: [
    refused('<select v-model="pick"><option value="a">A</option></select>', 'select'),
    refused('<textarea v-model="text" rows="4" />', 'textarea'),
    refused('<textarea class="x" :value="text" readonly></textarea>', 'textarea'),
    refused('<input v-model="name" />', 'input'),
    refused('<input type="text" v-model="name" />', 'input'),
    refused('<input type="password" />', 'input'),
    refused('<input type="email" />', 'input'),
    refused('<input type="search" />', 'input'),
    refused('<input type="number" />', 'input'),
    refused('<input type="date" />', 'input'),
    refused('<input type="datetime-local" />', 'input'),
    refused('<input type="time" />', 'input'),
    refused('<input type="url" />', 'input'),
    refused('<input type="tel" />', 'input'),
    refused('<input type="color" />', 'input'),
    refused('<input :type="shown ? \'text\' : \'password\'" />', 'boundType'),
    refused('<input v-bind:type="kind" />', 'boundType'),
    {
      ...template('<select v-model="a" /><textarea v-model="b" /><input type="text" /><input type="file" />'),
      errors: [{ messageId: 'select' }, { messageId: 'textarea' }, { messageId: 'input' }],
    },
  ],
})
