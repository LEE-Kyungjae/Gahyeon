#!/usr/bin/env node

import { readdir, readFile, rename, writeFile, mkdir } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import process from 'node:process'

export const DEFAULT_LIMITS = Object.freeze({
  minimumCases: 20,
  minimumUniqueSources: 10,
  maximumFirstPartialP95Ms: 1_200,
  maximumVadFinalP95Ms: 1_500,
  maximumMeanCer: 0.12,
  maximumWorstCer: 0.30,
  minimumPartialCoverage: 0.80,
})

export function percentile(values, proportion) {
  if (values.length === 0) throw new Error('percentile requires at least one value')
  const ordered = [...values].sort((left, right) => left - right)
  return ordered[Math.max(0, Math.ceil(proportion * ordered.length) - 1)]
}

export function summarize(reports, limits) {
  const completed = reports.filter(report => report.status === 'completed')
  const failed = reports.filter(report => report.status === 'failed')
  const firstPartial = completed
    .map(report => report.latency.startToFirstPartialMs)
    .filter(value => Number.isFinite(value))
  const startToFinal = completed.map(report => report.latency.startToFinalMs)
  const vadEndToFinal = completed.map(report => report.latency.vadEndToFinalMs)
  const cers = completed.map(report => report.result.cer)
  const uniqueSources = new Set(completed.map(report => report.source.sha256)).size
  const metrics = completed.length === 0 ? null : {
    startToFirstPartialMs: firstPartial.length === 0 ? null : {
      p50: percentile(firstPartial, 0.50),
      p95: percentile(firstPartial, 0.95),
      worst: Math.max(...firstPartial),
    },
    startToFinalMs: {
      p50: percentile(startToFinal, 0.50),
      p95: percentile(startToFinal, 0.95),
      worst: Math.max(...startToFinal),
    },
    vadEndToFinalMs: {
      p50: percentile(vadEndToFinal, 0.50),
      p95: percentile(vadEndToFinal, 0.95),
      worst: Math.max(...vadEndToFinal),
    },
    cer: {
      mean: Math.round(cers.reduce((sum, value) => sum + value, 0) / cers.length * 10_000) / 10_000,
      p95: percentile(cers, 0.95),
      worst: Math.max(...cers),
    },
    partialCoverage: Math.round(firstPartial.length / completed.length * 10_000) / 10_000,
  }
  const violations = []
  if (reports.length < limits.minimumCases) violations.push('minimum_cases')
  if (uniqueSources < limits.minimumUniqueSources) violations.push('minimum_unique_sources')
  if (failed.length > 0) violations.push('failed_cases')
  if (!metrics) {
    violations.push('no_completed_cases')
  } else {
    if (!metrics.startToFirstPartialMs
        || metrics.startToFirstPartialMs.p95 > limits.maximumFirstPartialP95Ms) {
      violations.push('first_partial_p95')
    }
    if (metrics.vadEndToFinalMs.p95 > limits.maximumVadFinalP95Ms) {
      violations.push('vad_end_to_final_p95')
    }
    if (metrics.cer.mean > limits.maximumMeanCer) violations.push('mean_cer')
    if (metrics.cer.worst > limits.maximumWorstCer) violations.push('worst_cer')
    if (metrics.partialCoverage < limits.minimumPartialCoverage) {
      violations.push('partial_coverage')
    }
  }
  return {
    schemaVersion: 1,
    status: violations.length === 0 ? 'accepted' : 'rejected',
    observedAt: new Date().toISOString(),
    counts: { total: reports.length, completed: completed.length, failed: failed.length, uniqueSources },
    limits,
    metrics,
    violations,
  }
}

function parseArguments(argv) {
  const values = new Map()
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index]
    if (!key.startsWith('--') || index + 1 >= argv.length) throw new Error(`invalid argument near ${key}`)
    values.set(key.slice(2), argv[++index])
  }
  if (!values.get('input') || !values.get('output')) throw new Error('--input and --output are required')
  const number = (name, fallback) => {
    const result = Number(values.get(name) ?? fallback)
    if (!Number.isFinite(result) || result < 0) throw new Error(`--${name} must be non-negative`)
    return result
  }
  return {
    input: resolve(values.get('input')),
    output: resolve(values.get('output')),
    limits: {
      minimumCases: number('minimum-cases', DEFAULT_LIMITS.minimumCases),
      minimumUniqueSources: number('minimum-unique-sources', DEFAULT_LIMITS.minimumUniqueSources),
      maximumFirstPartialP95Ms: number('maximum-first-partial-p95-ms', DEFAULT_LIMITS.maximumFirstPartialP95Ms),
      maximumVadFinalP95Ms: number('maximum-vad-final-p95-ms', DEFAULT_LIMITS.maximumVadFinalP95Ms),
      maximumMeanCer: number('maximum-mean-cer', DEFAULT_LIMITS.maximumMeanCer),
      maximumWorstCer: number('maximum-worst-cer', DEFAULT_LIMITS.maximumWorstCer),
      minimumPartialCoverage: number('minimum-partial-coverage', DEFAULT_LIMITS.minimumPartialCoverage),
    },
  }
}

async function writeAtomic(path, payload) {
  await mkdir(dirname(path), { recursive: true })
  const temporary = `${path}.${process.pid}.tmp`
  await writeFile(temporary, `${JSON.stringify(payload, null, 2)}\n`, 'utf8')
  await rename(temporary, path)
}

async function main() {
  const options = parseArguments(process.argv.slice(2))
  const names = (await readdir(options.input)).filter(name => name.endsWith('.json')).sort()
  const reports = []
  for (const name of names) {
    const report = JSON.parse(await readFile(resolve(options.input, name), 'utf8'))
    if (report.schemaVersion !== 1 || !['completed', 'failed'].includes(report.status)) {
      throw new Error(`not a Streaming STT case report: ${name}`)
    }
    reports.push(report)
  }
  const summary = summarize(reports, options.limits)
  await writeAtomic(options.output, summary)
  console.log(JSON.stringify(summary))
  if (summary.status !== 'accepted') process.exitCode = 2
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch(error => {
    console.error(`${error?.name || 'Error'}: ${error?.message || error}`)
    process.exitCode = 1
  })
}
