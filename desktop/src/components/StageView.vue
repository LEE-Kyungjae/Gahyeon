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
  characterWindow?: boolean
  characterMediaUrl?: string
  characterId?: string
  characterName?: string
}>()
const emit = defineEmits<{
  worldActionArrived: [action: PendingWorldAction]
  rendererPresence: [present: boolean]
}>()

const host = ref<HTMLElement>()
const characterMedia = ref<HTMLVideoElement>()
const lookingGlassControls = ref<HTMLElement>()
const modelError = ref('')
const lookingGlassReady = ref(false)
const lookingGlassLoading = ref(false)
let stage: ThreeStage | undefined
let mounted = false

onMounted(async () => {
  mounted = true
  if (!host.value) return
  if (props.characterWindow && props.characterMediaUrl) {
    emit('rendererPresence', true)
    return
  }
  stage = new ThreeStage(
    host.value,
    props.state,
    action => emit('worldActionArrived', action),
    { transparent: props.characterWindow },
  )
  emit('rendererPresence', true)
  if (props.worldUrl && !props.characterWindow) {
    try {
      const environment = await GltfWorldEnvironment.load(props.worldUrl)
      if (mounted) stage.setEnvironment(environment)
      else environment.dispose()
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
    if (!mounted) {
      character.dispose()
      return
    }
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
onBeforeUnmount(() => {
  mounted = false
  emit('rendererPresence', false)
  stage?.dispose()
})

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

function setCameraPreset(preset: 'face' | 'bust' | 'full-body') {
  stage?.setCameraPreset(preset)
}
</script>

<template>
  <div ref="host" class="stage-canvas" />
  <video
    v-if="characterWindow && characterMediaUrl"
    ref="characterMedia"
    class="character-media"
    :class="{
      [`character-${characterId ?? 'gahyeon'}`]: true,
      speaking: state.speaking,
      listening: state.activity === 'listening',
      thinking: state.activity === 'thinking',
      'conversation-framing': state.speaking
        || state.activity === 'attention'
        || state.activity === 'listening'
        || state.activity === 'thinking'
        || state.activity === 'conversation',
    }"
    :src="characterMediaUrl"
    loop
    autoplay
    muted
    playsinline
    :aria-label="`${characterName ?? 'Gahyeon'} character preview`"
  />
  <div v-if="lookingGlassEnabled" ref="lookingGlassControls" class="looking-glass-controls">
    <button
      v-if="!lookingGlassReady"
      type="button"
      class="looking-glass-enable"
      :disabled="lookingGlassLoading"
      @click="enableLookingGlass"
    >{{ lookingGlassLoading ? t('stage.loading') : t('stage.enableLookingGlass') }}</button>
  </div>
  <nav v-if="characterWindow && !characterMediaUrl" class="camera-presets" aria-label="Character framing">
    <button type="button" @click="setCameraPreset('face')">Face</button>
    <button type="button" @click="setCameraPreset('bust')">Bust</button>
    <button type="button" @click="setCameraPreset('full-body')">Full</button>
  </nav>
  <p v-if="modelError" class="model-error">{{ modelError }}</p>
</template>
