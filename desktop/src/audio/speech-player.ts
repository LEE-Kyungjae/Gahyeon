import type { GahyeonDesktopBridge } from '../gahyeon-api'

export interface SpeechPlaybackListener {
  onStart(): void
  onLevel(level: number): void
  onStop(): void
}

export class SpeechPlayer {
  private context?: AudioContext
  private activeSource?: AudioBufferSourceNode
  private generation = 0

  async speak(
    text: string,
    bridge: GahyeonDesktopBridge,
    listener: SpeechPlaybackListener,
  ) {
    this.stop()
    const generation = this.generation
    this.context ??= new AudioContext()
    await this.context.resume()
    const segments = await bridge.prepareSpeech(text)
    listener.onStart()
    try {
      for (const segment of segments) {
        if (generation !== this.generation) break
        const audio = await bridge.synthesizeSpeech(segment)
        if (generation !== this.generation) break
        const decoded = await this.context.decodeAudioData(audio.data.slice(0))
        await this.playBuffer(decoded, listener)
      }
    }
    finally {
      if (generation === this.generation) {
        this.activeSource = undefined
        listener.onLevel(0)
        listener.onStop()
      }
    }
  }

  stop() {
    this.generation++
    try {
      this.activeSource?.stop()
    } catch {
      // The source may already have naturally ended.
    }
    this.activeSource = undefined
  }

  async dispose() {
    this.stop()
    await this.context?.close()
    this.context = undefined
  }

  private playBuffer(buffer: AudioBuffer, listener: SpeechPlaybackListener) {
    if (!this.context) return Promise.resolve()
    const source = this.context.createBufferSource()
    const analyser = this.context.createAnalyser()
    analyser.fftSize = 256
    analyser.smoothingTimeConstant = 0.55
    source.buffer = buffer
    source.connect(analyser)
    analyser.connect(this.context.destination)
    this.activeSource = source

    return new Promise<void>((resolve) => {
      const levels = new Uint8Array(analyser.fftSize)
      let frame = 0
      const sample = () => {
        analyser.getByteTimeDomainData(levels)
        let energy = 0
        for (const value of levels) {
          const normalized = (value - 128) / 128
          energy += normalized * normalized
        }
        const rms = Math.sqrt(energy / levels.length)
        listener.onLevel(Math.min(1, Math.max(0, (rms - 0.015) * 7.5)))
        frame = requestAnimationFrame(sample)
      }
      source.onended = () => {
        cancelAnimationFrame(frame)
        source.disconnect()
        analyser.disconnect()
        resolve()
      }
      source.start()
      frame = requestAnimationFrame(sample)
    })
  }
}
