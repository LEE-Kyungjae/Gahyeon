import { GahyeonClientError } from '../client-error'
import { VoiceActivityDetector } from '../runtime/voice-activity-detector'

export interface RecordingActivityListener {
  onVoiceStarted?(): void
  onVoiceEnded?(): void
}

export class WavRecorder {
  private context?: AudioContext
  private stream?: MediaStream
  private source?: MediaStreamAudioSourceNode
  private processor?: ScriptProcessorNode
  private readonly samples: Float32Array[] = []
  private sampleRate = 48_000

  get active() {
    return this.stream !== undefined
  }

  async start(listener: RecordingActivityListener = {}) {
    if (this.active) return
    this.samples.length = 0
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
    })
    this.context = new AudioContext()
    await this.context.resume()
    this.sampleRate = this.context.sampleRate
    this.source = this.context.createMediaStreamSource(this.stream)
    const vad = new VoiceActivityDetector({
      startThreshold: 0.04,
      stopThreshold: 0.02,
      attackMs: 60,
      releaseMs: 450,
    })
    const preRollFrames = Math.ceil(this.sampleRate * 0.25 / 2048)
    let heardVoice = false
    this.processor = this.context.createScriptProcessor(2048, 1, 1)
    this.processor.onaudioprocess = (event) => {
      const frame = new Float32Array(event.inputBuffer.getChannelData(0))
      this.samples.push(frame)
      const activity = vad.observe(rmsLevel(frame), this.context!.currentTime * 1_000)
      if (activity === 'started') {
        heardVoice = true
        if (this.samples.length > preRollFrames) {
          this.samples.splice(0, this.samples.length - preRollFrames)
        }
        listener.onVoiceStarted?.()
      }
      if (activity === 'ended') listener.onVoiceEnded?.()
      if (!heardVoice && this.samples.length > preRollFrames) {
        this.samples.splice(0, this.samples.length - preRollFrames)
      }
      event.outputBuffer.getChannelData(0).fill(0)
    }
    this.source.connect(this.processor)
    this.processor.connect(this.context.destination)
  }

  async stop(): Promise<ArrayBuffer> {
    if (!this.active || !this.context) throw new GahyeonClientError('recorderInactive')
    this.processor?.disconnect()
    this.source?.disconnect()
    this.stream?.getTracks().forEach(track => track.stop())
    await this.context.close()
    const audio = merge(this.samples)
    this.context = undefined
    this.stream = undefined
    this.source = undefined
    this.processor = undefined
    if (audio.length < this.sampleRate * 0.15) throw new GahyeonClientError('recordingShort')
    return encodePcm16Wav(audio, this.sampleRate)
  }

  async cancel() {
    if (!this.active) return
    this.samples.length = 0
    this.processor?.disconnect()
    this.source?.disconnect()
    this.stream?.getTracks().forEach(track => track.stop())
    await this.context?.close()
    this.context = undefined
    this.stream = undefined
    this.source = undefined
    this.processor = undefined
  }
}

export function rmsLevel(samples: Float32Array) {
  if (samples.length === 0) return 0
  let energy = 0
  for (const sample of samples) energy += sample * sample
  return Math.min(1, Math.sqrt(energy / samples.length))
}

function merge(chunks: Float32Array[]) {
  const length = chunks.reduce((total, chunk) => total + chunk.length, 0)
  const merged = new Float32Array(length)
  let offset = 0
  for (const chunk of chunks) {
    merged.set(chunk, offset)
    offset += chunk.length
  }
  return merged
}

export function encodePcm16Wav(samples: Float32Array, sampleRate: number): ArrayBuffer {
  const buffer = new ArrayBuffer(44 + samples.length * 2)
  const view = new DataView(buffer)
  writeAscii(view, 0, 'RIFF')
  view.setUint32(4, 36 + samples.length * 2, true)
  writeAscii(view, 8, 'WAVE')
  writeAscii(view, 12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, 1, true)
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  writeAscii(view, 36, 'data')
  view.setUint32(40, samples.length * 2, true)
  for (let index = 0; index < samples.length; index++) {
    const sample = Math.max(-1, Math.min(1, samples[index] ?? 0))
    view.setInt16(44 + index * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true)
  }
  return buffer
}

function writeAscii(view: DataView, offset: number, value: string) {
  for (let index = 0; index < value.length; index++) {
    view.setUint8(offset + index, value.charCodeAt(index))
  }
}
