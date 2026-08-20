import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

describe('character window voice contract', () => {
  const app = readFileSync(new URL('./App.vue', import.meta.url), 'utf8')
  const controls = readFileSync(new URL('./CharacterControlPanel.vue', import.meta.url), 'utf8')
  const stage = readFileSync(new URL('./components/StageView.vue', import.meta.url), 'utf8')

  it('keeps AIRI-style controls inline and opens picker and chat separately', () => {
    expect(app).toContain('class="character-controls-island"')
    expect(app).toContain('@click="gahyeon.openControlsPanel()"')
    expect(app).toContain('@click="gahyeon.openChatWindow()"')
    expect(app).toContain('@click="toggleRecording"')
    expect(app).toContain('@click="toggleVoiceOutput"')
    expect(app).not.toContain('character-drag-handle')
    expect(controls).toContain('캐릭터 변경')
    expect(controls).toContain('aria-label="컨트롤 접기"')
    expect(app).toContain("'캐릭터 컨트롤 펼치기'")
  })

  it('keeps continuous microphone and TTS controls in the chat window', () => {
    expect(app).toContain('class="mic-button"')
    expect(app).toContain('@click="toggleRecording"')
    expect(app).toContain('microphoneEnabled.value')
    expect(app).toContain('await startContinuousListening()')
    expect(app).toContain('class="voice-toggle"')
    expect(app).toContain('@click="toggleVoiceOutput"')
  })

  it('keeps living-character media moving between speech events', () => {
    expect(stage).toContain('autoplay')
    expect(stage).toContain('loop')
    expect(stage).not.toContain('characterMedia.value.pause()')
  })

  it('uses conversational states to switch from full-body to bust framing', () => {
    expect(stage).toContain("'conversation-framing': state.speaking")
    expect(stage).toContain("state.activity === 'attention'")
    expect(stage).toContain("state.activity === 'listening'")
    expect(stage).toContain("state.activity === 'thinking'")
    expect(stage).toContain("state.activity === 'conversation'")
  })
})
