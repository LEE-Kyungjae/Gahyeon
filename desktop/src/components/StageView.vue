<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { PendingWorldAction, StageState } from '../stage/stage-state'
import { ThreeStage } from '../stage/three-stage'
import { GltfWorldEnvironment } from '../stage/world-environment'
import { t } from '../i18n'
import { localizedError } from '../client-error'

const props = defineProps<{
  state: StageState
  modelUrl?: string
  heroManifestUrl?: string
  animationManifestUrl?: string
  worldUrl?: string
  lookingGlassEnabled?: boolean
}>()
const emit = defineEmits<{
  worldActionArrived: [action: PendingWorldAction]
}>()

const host = ref<HTMLElement>()
const lookingGlassControls = ref<HTMLElement>()
const modelError = ref('')
const lookingGlassReady = ref(false)
const lookingGlassLoading = ref(false)
let stage: ThreeStage | undefined

onMounted(async () => {
  if (!host.value) return
  stage = new ThreeStage(
    host.value,
    props.state,
    action => emit('worldActionArrived', action),
  )
  if (props.worldUrl) {
    try {
      stage.setEnvironment(await GltfWorldEnvironment.load(props.worldUrl))
    }
    catch (error) {
      modelError.value = t('stage.worldFailure', { details: localizedError(error) })
    }
  }
  if (!props.heroManifestUrl && !props.modelUrl) return
  try {
    const { VrmCharacterRenderer } = await import('../stage/vrm-character')
    let verified: { objectUrl: string; revoke(): void } | undefined
    if (props.heroManifestUrl) {
      const { loadApprovedHeroPackage } = await import('../stage/hero-asset-loader')
      verified = await loadApprovedHeroPackage(props.heroManifestUrl, 'three-vrm')
    }
    const character = await VrmCharacterRenderer.load(
      verified?.objectUrl ?? props.modelUrl!, props.animationManifestUrl,
    ).finally(() => verified?.revoke())
    stage.setCharacter(character)
    if (character.animationWarnings.length > 0) {
      modelError.value = t('stage.partialAnimation', { details: character.animationWarnings.join('; ') })
    }
  }
  catch (error) {
    modelError.value = localizedError(error)
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
    modelError.value = t('stage.lookingGlassFailure', { details: localizedError(error) })
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
