<script setup lang="ts">
// The entries of a `VeyqMenu` and of its submenus (see there).
import { inject } from 'vue'
import { DropdownMenuItem, DropdownMenuPortal, DropdownMenuSeparator, DropdownMenuSub, DropdownMenuSubContent, DropdownMenuSubTrigger } from 'reka-ui'
import VeyqIcon from './VeyqIcon.vue'
import { MENU_PICK, type VeyqMenuItem } from './menu'

defineProps<{ items: VeyqMenuItem[] }>()
const pick = inject(MENU_PICK, (run: () => void) => run())
</script>

<template>
  <template v-for="(item, i) in items" :key="item.key ?? i">
    <DropdownMenuSeparator v-if="item.separator" class="veyq-menu__separator" />
    <DropdownMenuSub v-else-if="item.items?.length">
      <DropdownMenuSubTrigger :class="['veyq-menu__item', item.class]" :disabled="item.disabled">
        <div class="veyq-menu__item-content">
          <span class="veyq-menu__link">
            <VeyqIcon v-if="item.icon" :name="item.icon" class="veyq-menu__item-icon" />
            <span class="veyq-menu__item-label">{{ item.label }}</span>
            <VeyqIcon name="chevron-right" class="veyq-menu__submenu-icon" />
          </span>
        </div>
      </DropdownMenuSubTrigger>
      <DropdownMenuPortal>
        <DropdownMenuSubContent class="veyq-menu veyq-menu--sub" :side-offset="4" :collision-padding="8">
          <VeyqMenuEntries :items="item.items.filter((s) => s.visible !== false)" />
        </DropdownMenuSubContent>
      </DropdownMenuPortal>
    </DropdownMenuSub>
    <DropdownMenuItem
      v-else
      :class="['veyq-menu__item', item.class]"
      :disabled="item.disabled"
      @select="(event: Event) => item.command && pick(() => item.command!(event))"
    >
      <div class="veyq-menu__item-content">
        <a v-if="item.url" class="veyq-menu__link" :href="item.url" :target="item.target">
          <VeyqIcon v-if="item.icon" :name="item.icon" class="veyq-menu__item-icon" />
          <span class="veyq-menu__item-label">{{ item.label }}</span>
        </a>
        <span v-else class="veyq-menu__link">
          <VeyqIcon v-if="item.icon" :name="item.icon" class="veyq-menu__item-icon" />
          <span class="veyq-menu__item-label">{{ item.label }}</span>
          <span v-if="item.badge" class="veyq-menu__badge">{{ item.badge }}</span>
        </span>
      </div>
    </DropdownMenuItem>
  </template>
</template>
