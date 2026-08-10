import { describe, expect, it } from 'vitest'
import { encodePcm16Wav } from './wav-recorder'

describe('PCM WAV encoder', () => {
  it('writes a mono PCM16 WAV header and clamps samples', () => {
    const wav = encodePcm16Wav(new Float32Array([-2, 0, 2]), 48_000)
    const view = new DataView(wav)
    const text = (offset: number, length: number) => String.fromCharCode(
      ...new Uint8Array(wav, offset, length),
    )

    expect(text(0, 4)).toBe('RIFF')
    expect(text(8, 4)).toBe('WAVE')
    expect(view.getUint16(22, true)).toBe(1)
    expect(view.getUint32(24, true)).toBe(48_000)
    expect(view.getUint16(34, true)).toBe(16)
    expect(view.getInt16(44, true)).toBe(-32768)
    expect(view.getInt16(48, true)).toBe(32767)
  })
})
