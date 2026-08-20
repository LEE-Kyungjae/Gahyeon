<script setup lang="ts">
import { ref } from 'vue'
import {
  desktopCharacters,
  DESKTOP_CHARACTER_STORAGE_KEY,
  restoreDesktopCharacter,
  type DesktopCharacterId,
} from './character-catalog'
import { getGahyeonBridge } from './gahyeon-api'

const selected = ref(restoreDesktopCharacter(localStorage))
const gahyeon = getGahyeonBridge()

function selectCharacter(id: DesktopCharacterId) {
  selected.value = id
  localStorage.setItem(DESKTOP_CHARACTER_STORAGE_KEY, id)
}

</script>

<template>
  <main class="character-control-panel">
    <header>
      <div>
        <span>GAHYEON DESKTOP</span>
        <strong>캐릭터 변경</strong>
      </div>
      <button class="panel-collapse" type="button" aria-label="컨트롤 접기" @click="gahyeon.closeCurrentWindow()">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
      </button>
    </header>

    <section class="character-card-list" aria-label="캐릭터 선택">
      <button
        v-for="character in desktopCharacters"
        :key="character.id"
        type="button"
        :class="{ selected: selected === character.id }"
        :aria-pressed="selected === character.id"
        @click="selectCharacter(character.id)"
      >
        <span class="character-card-mark">{{ character.displayName.slice(0, 1) }}</span>
        <span><strong>{{ character.displayName }}</strong><small>3D CHARACTER</small></span>
        <span class="character-card-check">{{ selected === character.id ? '✓' : '' }}</span>
      </button>
    </section>

  </main>
</template>
