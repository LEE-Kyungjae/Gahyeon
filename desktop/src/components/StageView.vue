<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { StageState } from '../stage/stage-state'
import { ThreeStage } from '../stage/three-stage'

const props = defineProps<{
  state: StageState
  modelUrl?: string
}>()

const host = ref<HTMLElement>()
const modelError = ref('')
let stage: ThreeStage | undefined

onMounted(async () => {
  if (!host.value) return
  stage = new ThreeStage(host.value, props.state)
  if (!props.modelUrl) return
  try {
    const { VrmCharacterRenderer } = await import('../stage/vrm-character')
    stage.setCharacter(await VrmCharacterRenderer.load(props.modelUrl))
  }
  catch (error) {
    modelError.value = error instanceof Error ? error.message : String(error)
  }
})

watch(() => props.state, state => stage?.setState(state), { deep: true })
onBeforeUnmount(() => stage?.dispose())
</script>

<template>
  <div ref="host" class="stage-canvas" />
  <p v-if="modelError" class="model-error">{{ modelError }}</p>
</template>
