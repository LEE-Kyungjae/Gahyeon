#!/usr/bin/env node

import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { performance } from 'node:perf_hooks'
import process from 'node:process'
import WebSocket from 'ws'

export function normalized(text) {
  return text.normalize('NFKC').toLocaleLowerCase('ko-KR')
    .replace(/[^\p{Letter}\p{Number}]/gu, '')
}

export function editDistance(left, right) {
  let previous = Array.from({ length: right.length + 1 }, (_, index) => index)
  for (let row = 1; row <= left.length; row += 1) {
    const current = [row]
    for (let column = 1; column <= right.length; column += 1) {
      current.push(Math.min(
        current[column - 1] + 1,
        previous[column] + 1,
        previous[column - 1] + (left[row - 1] === right[column - 1] ? 0 : 1),
      ))
    }
    previous = current
  }
  return previous[right.length]
}

export function parseWav(buffer) {
  if (buffer.length < 44 || buffer.toString('ascii', 0, 4) !== 'RIFF'
      || buffer.toString('ascii', 8, 12) !== 'WAVE') {
    throw new Error('input is not a RIFF/WAVE file')
  }
  let offset = 12
  let format
  let data
  while (offset + 8 <= buffer.length) {
    const id = buffer.toString('ascii', offset, offset + 4)
    const size = buffer.readUInt32LE(offset + 4)
    const start = offset + 8
    const end = start + size
    if (end > buffer.length) throw new Error(`truncated WAV chunk: ${id}`)
    if (id === 'fmt ' && size >= 16) {
      format = {
        encoding: buffer.readUInt16LE(start),
        channels: buffer.readUInt16LE(start + 2),
        sampleRate: buffer.readUInt32LE(start + 4),
        blockAlign: buffer.readUInt16LE(start + 12),
        bitsPerSample: buffer.readUInt16LE(start + 14),
      }
    } else if (id === 'data') {
      data = buffer.subarray(start, end)
    }
    offset = end + (size & 1)
  }
  if (!format || !data) throw new Error('WAV requires fmt and data chunks')
  if (format.channels < 1 || format.channels > 8
      || format.sampleRate < 8_000 || format.sampleRate > 192_000) {
    throw new Error('WAV channel count or sample rate is outside the STT contract')
  }
  const supported = (format.encoding === 1 && format.bitsPerSample === 16)
    || (format.encoding === 3 && format.bitsPerSample === 32)
  if (!supported) throw new Error('only PCM16 and IEEE float32 WAV are supported')
  const bytesPerSample = format.bitsPerSample / 8
  if (format.blockAlign !== format.channels * bytesPerSample
      || data.length === 0 || data.length % format.blockAlign !== 0) {
    throw new Error('WAV block alignment is invalid')
  }
  const samples = new Float32Array(data.length / bytesPerSample)
  for (let index = 0; index < samples.length; index += 1) {
    const byteOffset = index * bytesPerSample
    let value = format.encoding === 1
      ? data.readInt16LE(byteOffset) / 32768
      : data.readFloatLE(byteOffset)
    if (!Number.isFinite(value)) value = 0
    samples[index] = Math.max(-1, Math.min(1, value))
  }
  return {
    sampleRate: format.sampleRate,
    channels: format.channels,
    frames: samples.length / format.channels,
    samples,
  }
}

export function audioFrame(sequence, samples) {
  const result = Buffer.allocUnsafe(8 + samples.length * 4)
  result.writeBigUInt64BE(BigInt(sequence), 0)
  for (let index = 0; index < samples.length; index += 1) {
    result.writeFloatLE(samples[index], 8 + index * 4)
  }
  return result
}

function argumentsOf(argv) {
  const values = new Map()
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index]
    if (!key.startsWith('--') || index + 1 >= argv.length) {
      throw new Error(`invalid argument near ${key}`)
    }
    values.set(key.slice(2), argv[++index])
  }
  for (const required of ['event-url', 'stt-url', 'wav', 'expected', 'output']) {
    if (!values.get(required)) throw new Error(`--${required} is required`)
  }
  return {
    eventUrl: values.get('event-url'),
    sttUrl: values.get('stt-url'),
    wav: resolve(values.get('wav')),
    expected: values.get('expected'),
    output: resolve(values.get('output')),
    token: values.get('token') || process.env.GAHYEON_CLIENT_TOKEN || '',
    sessionId: values.get('session') || `stt-eval-${randomUUID()}`,
    worldId: values.get('world') || 'gahyeon-home',
    framesPerChunk: Number(values.get('frames-per-chunk') || 320),
    timeoutMs: Number(values.get('timeout-ms') || 30_000),
    pace: Number(values.get('pace') || 1),
  }
}

function deferred() {
  let resolvePromise
  let rejectPromise
  const promise = new Promise((resolve, reject) => {
    resolvePromise = resolve
    rejectPromise = reject
  })
  return { promise, resolve: resolvePromise, reject: rejectPromise }
}

function openSocket(url, token) {
  return new Promise((resolvePromise, rejectPromise) => {
    const headers = token ? { Authorization: `Bearer ${token}` } : undefined
    const socket = new WebSocket(url, { headers, maxPayload: 1_048_576 })
    socket.once('open', () => resolvePromise(socket))
    socket.once('error', rejectPromise)
  })
}

function wait(milliseconds) {
  return new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds))
}

async function writeAtomic(path, payload) {
  await mkdir(dirname(path), { recursive: true })
  const temporary = `${path}.${process.pid}.tmp`
  await writeFile(temporary, `${JSON.stringify(payload, null, 2)}\n`, 'utf8')
  await rename(temporary, path)
}

export async function evaluate(options) {
  if (!Number.isInteger(options.framesPerChunk)
      || options.framesPerChunk < 64 || options.framesPerChunk > 4096) {
    throw new Error('frames-per-chunk must be an integer from 64 through 4096')
  }
  if (!(options.pace > 0 && options.pace <= 10) || !(options.timeoutMs >= 1_000)) {
    throw new Error('pace must be (0,10] and timeout-ms must be at least 1000')
  }
  const wavBytes = await readFile(options.wav)
  const wav = parseWav(wavBytes)
  const streamId = `eval-${randomUUID()}`
  const generation = 1
  const welcome = deferred()
  const result = deferred()
  const started = performance.now()
  let firstPartialAt
  let endSentAt
  let eventSocket
  let sttSocket
  const partials = []
  const timeout = setTimeout(() => result.reject(new Error('streaming STT final timed out')),
    options.timeoutMs)
  try {
    eventSocket = await openSocket(options.eventUrl, options.token)
    eventSocket.on('message', raw => {
      try {
        const message = JSON.parse(raw.toString())
        if (message.type === 'server.welcome') welcome.resolve(message)
      } catch (error) {
        welcome.reject(error)
      }
    })
    eventSocket.once('close', () => welcome.reject(new Error('event socket closed before welcome')))
    eventSocket.send(JSON.stringify({
      protocol: 'gahyeon.unreal.v1', schemaVersion: 1,
      messageId: randomUUID(), type: 'client.hello', sentAt: new Date().toISOString(),
      sessionId: options.sessionId, correlationId: `connection:${randomUUID()}`,
      delivery: 'ephemeral',
      payload: {
        sessionId: options.sessionId, worldId: options.worldId,
        installationId: 'streaming-stt-evaluator', displayName: 'Streaming STT evaluator',
        lastSequence: 0,
      },
    }))
    await Promise.race([welcome.promise, wait(options.timeoutMs).then(() => {
      throw new Error('event socket welcome timed out')
    })])

    sttSocket = await openSocket(options.sttUrl, options.token)
    sttSocket.on('message', raw => {
      try {
        const message = JSON.parse(raw.toString())
        if (message.streamId !== streamId || message.generation !== generation) return
        if (message.type === 'stt.transcript.partial') {
          if (firstPartialAt === undefined) firstPartialAt = performance.now()
          partials.push({
            sequence: message.resultSequence,
            elapsedMs: Math.round((performance.now() - started) * 100) / 100,
            text: message.text,
          })
        } else if (message.type === 'stt.transcript.final') {
          result.resolve(message)
        } else if (message.type === 'stt.stream.error') {
          result.reject(new Error(`streaming STT error: ${message.code}`))
        }
      } catch (error) {
        result.reject(error)
      }
    })
    sttSocket.once('close', () => result.reject(new Error('STT socket closed before final')))
    sttSocket.send(JSON.stringify({
      schemaVersion: 1, type: 'stt.stream.start', sessionId: options.sessionId,
      streamId, generation, observedAtMs: Math.round(performance.now()),
      format: {
        encoding: 'float32le', sampleRate: wav.sampleRate, channels: wav.channels,
        framesPerChunk: options.framesPerChunk,
      },
    }))

    const samplesPerChunk = options.framesPerChunk * wav.channels
    let sequence = 0
    const audioStarted = performance.now()
    for (let offset = 0; offset < wav.samples.length; offset += samplesPerChunk) {
      const samples = wav.samples.subarray(offset, Math.min(wav.samples.length, offset + samplesPerChunk))
      sttSocket.send(audioFrame(sequence, samples))
      sequence += 1
      const target = audioStarted
        + ((offset + samples.length) / wav.channels / wav.sampleRate * 1000 / options.pace)
      const delay = target - performance.now()
      if (delay > 0) await wait(delay)
    }
    endSentAt = performance.now()
    sttSocket.send(JSON.stringify({
      schemaVersion: 1, type: 'stt.stream.end', sessionId: options.sessionId,
      streamId, generation, observedAtMs: Math.round(performance.now()),
      lastAudioSequence: sequence - 1,
    }))
    const final = await result.promise
    const finalAt = performance.now()
    const expectedNormalized = normalized(options.expected)
    const actualNormalized = normalized(final.text)
    return {
      schemaVersion: 1,
      status: 'completed',
      observedAt: new Date().toISOString(),
      source: {
        wav: options.wav,
        sha256: createHash('sha256').update(wavBytes).digest('hex'),
        sampleRate: wav.sampleRate,
        channels: wav.channels,
        frames: wav.frames,
        durationMs: Math.round(wav.frames / wav.sampleRate * 100_000) / 100,
      },
      transport: { eventUrl: options.eventUrl, sttUrl: options.sttUrl, sessionId: options.sessionId },
      result: {
        expected: options.expected,
        transcript: final.text,
        language: final.language,
        cer: Math.round(editDistance(expectedNormalized, actualNormalized)
          / Math.max(1, expectedNormalized.length) * 10_000) / 10_000,
        partialCount: partials.length,
        partials,
      },
      latency: {
        startToFirstPartialMs: firstPartialAt === undefined ? null
          : Math.round((firstPartialAt - started) * 100) / 100,
        startToFinalMs: Math.round((finalAt - started) * 100) / 100,
        vadEndToFinalMs: Math.round((finalAt - endSentAt) * 100) / 100,
      },
    }
  } finally {
    clearTimeout(timeout)
    if (sttSocket?.readyState === WebSocket.OPEN) sttSocket.close(1000, 'evaluation complete')
    if (eventSocket?.readyState === WebSocket.OPEN) eventSocket.close(1000, 'evaluation complete')
  }
}

async function main() {
  let options
  try {
    options = argumentsOf(process.argv.slice(2))
    const report = await evaluate(options)
    await writeAtomic(options.output, report)
    console.log(JSON.stringify(report))
  } catch (error) {
    const report = {
      schemaVersion: 1, status: 'failed', observedAt: new Date().toISOString(),
      error: `${error?.name || 'Error'}: ${error?.message || error}`,
    }
    if (options?.output) await writeAtomic(options.output, report)
    console.error(JSON.stringify(report))
    process.exitCode = 1
  }
}

if (import.meta.url === `file://${process.argv[1]}`) await main()
