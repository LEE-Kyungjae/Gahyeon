<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { StageState } from '../stage/stage-state'
import { ThreeStage } from '../stage/three-stage'
import { t } from '../i18n'

const props = defineProps<{
  state: StageState
  modelUrl?: string
  animationManifestUrl?: string
  lookingGlassEnabled?: boolean
}>()

const host = ref<HTMLElement>()
const lookingGlassControls = ref<HTMLElement>()
const modelError = ref('')
const lookingGlassReady = ref(false)
const lookingGlassLoading = ref(false)
let stage: ThreeStage | undefined

onMounted(async () => {
  if (!host.value) return
  stage = new ThreeStage(host.value, props.state)
  if (!props.modelUrl) return
  try {
    const { VrmCharacterRenderer } = await import('../stage/vrm-character')
    const character = await VrmCharacterRenderer.load(
      props.modelUrl,
      props.animationManifestUrl,
    )
    stage.setCharacter(character)
    if (character.animationWarnings.length > 0) {
      modelError.value = t('stage.partialAnimation', { details: character.animationWarnings.join('; ') })
    }
  }
  catch (error) {
    modelError.value = error instanceof Error ? error.message : String(error)
  }
})

watch(() => props.state, state => stage?.setState(state), { deep: true })
onBeforeUnmount(() => stage?.dispose())

async function enableLookingGlass() {
  if (!stage || !lookingGlassControls.value || lookingGlassLoading.value) return
  lookingGlassLoading.value = true
  modelError.value = ''
  try {
    await stage.enableLookingGlass(lookingGlassControls.value)
    lookingGlassReady.value = true
  }
  catch (error) {
    modelError.value = t('stage.lookingGlassFailure', { details: error instanceof Error ? error.message : String(error) })
  }
  finally {
    lookingGlassLoading.value = false
  }
}
</script>

<template>
  <div ref="host" class="stage-canvas" />
  <div v-if="lookingGlassEnabled" ref="lookingGlassControls" class="looking-glass-controls">
    <button
      v-if="!lookingGlassReady"
      type="button"
      class="looking-glass-enable"
      :disabled="lookingGlassLoading"
      @click="enableLookingGlass"
    >{{ lookingGlassLoading ? t('stage.loading') : t('stage.enableLookingGlass') }}</button>
  </div>
  <p v-if="modelError" class="model-error">{{ modelError }}</p>
</template>
