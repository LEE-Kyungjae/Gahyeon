import type { GahyeonDesktopBridge, SpeechSegment, VoiceExpression } from '../gahyeon-api'

export interface SpeechPlaybackListener {
  onStart(): void
  onLevel(level: number): void
  onStop(): void
}

export interface SpeechSequence {
  enqueue(text: string): Promise<void>
  finish(): Promise<void>
  cancel(): void
}

export class SpeechPlayer {
  private static readonly maximumQueuedTexts = 64
  private context?: AudioContext
  private activeSource?: AudioBufferSourceNode
  private cancelActiveRequests?: () => void
  private generation = 0

  async speak(
    text: string,
    bridge: GahyeonDesktopBridge,
    listener: SpeechPlaybackListener,
  ) {
    const sequence = this.beginSequence(bridge, listener)
    await sequence.enqueue(text)
    await sequence.finish()
  }

  async speakExpressive(
    text: string,
    bridge: GahyeonDesktopBridge,
    listener: SpeechPlaybackListener,
    voiceProfile: string,
    expression: VoiceExpression,
  ) {
    const sequence = this.beginSequence(bridge, listener, voiceProfile, expression)
    await sequence.enqueue(text)
    await sequence.finish()
  }

  beginSequence(
    bridge: GahyeonDesktopBridge,
    listener: SpeechPlaybackListener,
    voiceProfile = 'gahyeon.assistant',
    expression?: VoiceExpression,
  ): SpeechSequence {
    this.stop()
    const generation = this.generation
    this.cancelActiveRequests = () => bridge.cancelSpeechRequests?.()
    this.context ??= new AudioContext()
    const context = this.context
    const ready = context.resume()
    let started = false
    let finished = false
    let failure: unknown
    let tail = Promise.resolve()
    type PreparedAudio = { buffer?: AudioBuffer, error?: unknown }
    type PreparedText = { segments: SpeechSegment[], first?: PreparedAudio, error?: unknown }
    type TextTask = { text: string, started: boolean, preparation?: Promise<PreparedText> }
    const tasks: TextTask[] = []
    let prefetchOwner: TextTask | undefined
    let mayPrefetchFollowingText = false

    const prepareAudio = async (segment: SpeechSegment): Promise<PreparedAudio> => {
      try {
        const audio = await bridge.synthesizeSpeech({ ...segment, voiceProfile, expression })
        if (generation !== this.generation) return {}
        return { buffer: await context.decodeAudioData(audio.data.slice(0)) }
      }
      catch (error) {
        return { error }
      }
    }

    const prepareText = async (text: string): Promise<PreparedText> => {
      try {
        await ready
        if (generation !== this.generation) return { segments: [] }
        const segments = await bridge.prepareSpeech(text)
        return {
          segments,
          first: segments.length > 0 ? await prepareAudio(segments[0]) : undefined,
        }
      }
      catch (error) {
        return { segments: [], error }
      }
    }

    const scheduleNextText = () => {
      if (prefetchOwner !== undefined) return
      const candidate = tasks.find(task => !task.started && task.preparation === undefined)
      if (!candidate) return
      prefetchOwner = candidate
      candidate.preparation = prepareText(candidate.text)
    }

    const enqueue = (text: string) => {
      if (finished || !text.trim()) return tail
      if (tasks.length >= SpeechPlayer.maximumQueuedTexts) {
        const rejection = failure ?? new Error('speech text queue capacity exceeded')
        failure ??= rejection
        const rejected = Promise.reject<void>(rejection)
        // Stream listeners intentionally enqueue without awaiting every sentence.
        // Mark this promise handled locally while still returning the rejection to
        // callers that do observe admission; finish() remains the aggregate fence.
        void rejected.catch(() => {})
        return rejected
      }
      const task: TextTask = { text, started: false }
      tasks.push(task)
      if (tasks.length === 1 || mayPrefetchFollowingText) scheduleNextText()
      tail = tail.then(async () => {
        if (failure !== undefined) return
        task.started = true
        const preparedText = await (task.preparation ?? prepareText(task.text))
        if (prefetchOwner === task) prefetchOwner = undefined
        if (preparedText.error !== undefined) throw preparedText.error
        const { segments } = preparedText
        let prepared: PreparedAudio | Promise<PreparedAudio> | undefined = preparedText.first
        for (let index = 0; index < segments.length; index++) {
          const result = await prepared
          if (result?.error !== undefined) throw result.error
          const decoded = result?.buffer
          if (generation !== this.generation || !decoded) return
          const nextPrepared = index + 1 < segments.length
            ? prepareAudio(segments[index + 1])
            : undefined
          mayPrefetchFollowingText = nextPrepared === undefined
          if (mayPrefetchFollowingText) scheduleNextText()
          await this.playBuffer(decoded, listener, () => {
            if (!started) {
              started = true
              listener.onStart()
            }
          })
          mayPrefetchFollowingText = false
          if (generation !== this.generation) return
          prepared = nextPrepared
        }
        tasks.shift()
      }).catch(error => {
        mayPrefetchFollowingText = false
        failure ??= error
      })
      return tail
    }

    return {
      enqueue,
      finish: async () => {
        if (finished) return tail
        finished = true
        try {
          await tail
          if (failure !== undefined) throw failure
        }
        finally {
          if (generation === this.generation) {
            this.cancelActiveRequests = undefined
            this.activeSource = undefined
            listener.onLevel(0)
            if (started) listener.onStop()
          }
        }
      },
      cancel: () => {
        if (generation === this.generation) this.stop()
        finished = true
      },
    }
  }

  stop(): Promise<boolean> {
    this.generation++
    this.cancelActiveRequests?.()
    this.cancelActiveRequests = undefined
    const source = this.activeSource
    this.activeSource = undefined
    if (!source) return Promise.resolve(false)
    return new Promise(resolve => {
      const previous = source.onended
      source.onended = (event) => {
        previous?.call(source, event)
        resolve(true)
      }
      try {
        source.stop()
      } catch {
        resolve(false)
      }
    })
  }

  async dispose() {
    await this.stop()
    await this.context?.close()
    this.context = undefined
  }

  private playBuffer(
    buffer: AudioBuffer,
    listener: SpeechPlaybackListener,
    onStarted: () => void,
  ) {
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
      onStarted()
      frame = requestAnimationFrame(sample)
    })
  }
}
