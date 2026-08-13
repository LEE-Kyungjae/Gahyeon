#!/usr/bin/env node

import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import process from 'node:process'

import { evaluate } from './evaluate-streaming-stt.mjs'
import { DEFAULT_LIMITS, summarize } from './summarize-streaming-stt.mjs'

export function parseSuite(text, baseDirectory) {
  const cases = []
  const identities = new Set()
  for (const [lineIndex, line] of text.split(/\r?\n/).entries()) {
    if (!line.trim()) continue
    let item
    try {
      item = JSON.parse(line)
    } catch (error) {
      throw new Error(`suite line ${lineIndex + 1} is not JSON: ${error.message}`)
    }
    const keys = Object.keys(item).sort()
    if (JSON.stringify(keys) !== JSON.stringify(['expected', 'id', 'repeats', 'wav'])) {
      throw new Error(`suite line ${lineIndex + 1} must contain exactly id, wav, expected, repeats`)
    }
    if (typeof item.id !== 'string' || !/^[A-Za-z0-9._-]{1,64}$/.test(item.id)
        || identities.has(item.id)) {
      throw new Error(`suite line ${lineIndex + 1} has an invalid or duplicate id`)
    }
    if (typeof item.wav !== 'string' || item.wav.length === 0
        || typeof item.expected !== 'string' || item.expected.trim().length === 0
        || !Number.isInteger(item.repeats) || item.repeats < 1 || item.repeats > 20) {
      throw new Error(`suite line ${lineIndex + 1} has invalid wav, expected or repeats`)
    }
    identities.add(item.id)
    cases.push({ ...item, expected: item.expected.trim(), wav: resolve(baseDirectory, item.wav) })
  }
  const trials = cases.reduce((sum, item) => sum + item.repeats, 0)
  if (cases.length === 0 || trials > 200) throw new Error('suite requires 1..200 total trials')
  return cases
}

function parseArguments(argv) {
  const values = new Map()
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index]
    if (!key.startsWith('--') || index + 1 >= argv.length) throw new Error(`invalid argument near ${key}`)
    values.set(key.slice(2), argv[++index])
  }
  for (const required of ['event-url', 'stt-url', 'suite', 'output']) {
    if (!values.get(required)) throw new Error(`--${required} is required`)
  }
  const numeric = (name, fallback) => {
    const result = Number(values.get(name) ?? fallback)
    if (!Number.isFinite(result) || result < 0) throw new Error(`--${name} must be non-negative`)
    return result
  }
  return {
    eventUrl: values.get('event-url'),
    sttUrl: values.get('stt-url'),
    suite: resolve(values.get('suite')),
    output: resolve(values.get('output')),
    token: values.get('token') || process.env.GAHYEON_CLIENT_TOKEN || '',
    worldId: values.get('world') || 'gahyeon-home',
    framesPerChunk: numeric('frames-per-chunk', 320),
    timeoutMs: numeric('timeout-ms', 30_000),
    pace: numeric('pace', 1),
    retryFailures: values.get('retry-failures') === 'true',
    limits: {
      minimumCases: numeric('minimum-cases', DEFAULT_LIMITS.minimumCases),
      minimumUniqueSources: numeric('minimum-unique-sources', DEFAULT_LIMITS.minimumUniqueSources),
      maximumFirstPartialP95Ms: numeric('maximum-first-partial-p95-ms', DEFAULT_LIMITS.maximumFirstPartialP95Ms),
      maximumVadFinalP95Ms: numeric('maximum-vad-final-p95-ms', DEFAULT_LIMITS.maximumVadFinalP95Ms),
      maximumMeanCer: numeric('maximum-mean-cer', DEFAULT_LIMITS.maximumMeanCer),
      maximumWorstCer: numeric('maximum-worst-cer', DEFAULT_LIMITS.maximumWorstCer),
      minimumPartialCoverage: numeric('minimum-partial-coverage', DEFAULT_LIMITS.minimumPartialCoverage),
    },
  }
}

async function writeAtomic(path, payload) {
  await mkdir(dirname(path), { recursive: true })
  const temporary = `${path}.${process.pid}.tmp`
  await writeFile(temporary, `${JSON.stringify(payload, null, 2)}\n`, 'utf8')
  await rename(temporary, path)
}

async function existingReport(path, wav, expected, retryFailures) {
  try {
    const report = JSON.parse(await readFile(path, 'utf8'))
    const bytes = await readFile(wav)
    const sha256 = createHash('sha256').update(bytes).digest('hex')
    if (report.status === 'failed' && !retryFailures
        && report.caseIdentity?.sha256 === sha256
        && report.caseIdentity?.expected === expected) return report
    if (report.status !== 'completed' || report.result.expected !== expected) return null
    return report.source.sha256 === sha256 ? report : null
  } catch {
    return null
  }
}

async function main() {
  const options = parseArguments(process.argv.slice(2))
  const cases = parseSuite(await readFile(options.suite, 'utf8'), dirname(options.suite))
  const caseRoot = resolve(options.output, 'cases')
  await mkdir(caseRoot, { recursive: true })
  const reports = []
  for (const item of cases) {
    for (let repeat = 1; repeat <= item.repeats; repeat += 1) {
      const reportPath = resolve(caseRoot, `${item.id}-${String(repeat).padStart(2, '0')}.json`)
      let report = await existingReport(reportPath, item.wav, item.expected, options.retryFailures)
      if (!report) {
        try {
          report = await evaluate({
            eventUrl: options.eventUrl,
            sttUrl: options.sttUrl,
            wav: item.wav,
            expected: item.expected,
            token: options.token,
            sessionId: `stt-eval-${item.id}-${repeat}-${randomUUID()}`,
            worldId: options.worldId,
            framesPerChunk: options.framesPerChunk,
            timeoutMs: options.timeoutMs,
            pace: options.pace,
          })
        } catch (error) {
          const bytes = await readFile(item.wav)
          report = {
            schemaVersion: 1,
            status: 'failed',
            observedAt: new Date().toISOString(),
            error: `${error?.name || 'Error'}: ${error?.message || error}`,
            caseIdentity: {
              wav: item.wav,
              sha256: createHash('sha256').update(bytes).digest('hex'),
              expected: item.expected,
            },
          }
        }
        await writeAtomic(reportPath, report)
      }
      reports.push(report)
      console.log(JSON.stringify({ id: item.id, repeat, status: report.status }))
    }
  }
  const summary = summarize(reports, options.limits)
  await writeAtomic(resolve(options.output, 'summary.json'), summary)
  console.log(JSON.stringify(summary))
  if (summary.status !== 'accepted') process.exitCode = 2
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch(error => {
    console.error(`${error?.name || 'Error'}: ${error?.message || error}`)
    process.exitCode = 1
  })
}
