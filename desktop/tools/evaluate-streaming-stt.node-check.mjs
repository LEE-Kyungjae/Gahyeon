import assert from 'node:assert/strict'
import test from 'node:test'

import { audioFrame, editDistance, normalized, parseWav } from './evaluate-streaming-stt.mjs'

function pcm16Wav(samples, sampleRate = 16_000, channels = 1) {
  const dataBytes = samples.length * 2
  const result = Buffer.alloc(44 + dataBytes)
  result.write('RIFF', 0); result.writeUInt32LE(36 + dataBytes, 4); result.write('WAVE', 8)
  result.write('fmt ', 12); result.writeUInt32LE(16, 16); result.writeUInt16LE(1, 20)
  result.writeUInt16LE(channels, 22); result.writeUInt32LE(sampleRate, 24)
  result.writeUInt32LE(sampleRate * channels * 2, 28); result.writeUInt16LE(channels * 2, 32)
  result.writeUInt16LE(16, 34); result.write('data', 36); result.writeUInt32LE(dataBytes, 40)
  samples.forEach((value, index) => result.writeInt16LE(value, 44 + index * 2))
  return result
}

test('normalization and Korean character edit distance are deterministic', () => {
  assert.equal(normalized('안녕, Gahyeon 2!'), '안녕gahyeon2')
  assert.equal(editDistance('가현', '가연'), 1)
})

test('PCM16 WAV converts to bounded float32 samples', () => {
  const wav = parseWav(pcm16Wav([-32768, 0, 32767], 16_000, 1))
  assert.equal(wav.sampleRate, 16_000)
  assert.equal(wav.frames, 3)
  assert.equal(wav.samples[0], -1)
  assert.ok(wav.samples[2] > 0.999)
})

test('binary frame is big-endian sequence followed by little-endian float32', () => {
  const frame = audioFrame(258, Float32Array.from([0.5, -0.25]))
  assert.equal(frame.readBigUInt64BE(0), 258n)
  assert.equal(frame.readFloatLE(8), 0.5)
  assert.equal(frame.readFloatLE(12), -0.25)
})

test('malformed or unsupported WAV fails closed', () => {
  assert.throws(() => parseWav(Buffer.from('not wav')), /RIFF\/WAVE/)
  const wav = pcm16Wav([0])
  wav.writeUInt16LE(24, 34)
  assert.throws(() => parseWav(wav), /PCM16/)
})
