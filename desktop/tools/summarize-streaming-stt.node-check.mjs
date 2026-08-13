import assert from 'node:assert/strict'
import test from 'node:test'

import { percentile, summarize } from './summarize-streaming-stt.mjs'

const limits = {
  minimumCases: 2,
  minimumUniqueSources: 2,
  maximumFirstPartialP95Ms: 1_200,
  maximumVadFinalP95Ms: 1_500,
  maximumMeanCer: 0.12,
  maximumWorstCer: 0.30,
  minimumPartialCoverage: 0.80,
}

function completed(sha256, first, final, vadFinal, cer) {
  return {
    schemaVersion: 1,
    status: 'completed',
    source: { sha256 },
    latency: { startToFirstPartialMs: first, startToFinalMs: final, vadEndToFinalMs: vadFinal },
    result: { cer },
  }
}

test('nearest-rank percentile does not interpolate away tail latency', () => {
  assert.equal(percentile([10, 20, 30, 40, 50], 0.95), 50)
  assert.equal(percentile([50, 10, 30, 20, 40], 0.50), 30)
})

test('accepts only a sufficiently diverse suite inside every budget', () => {
  const result = summarize([
    completed('a'.repeat(64), 400, 1_000, 300, 0.04),
    completed('b'.repeat(64), 500, 1_100, 350, 0.06),
  ], limits)
  assert.equal(result.status, 'accepted')
  assert.deepEqual(result.violations, [])
  assert.equal(result.metrics.cer.mean, 0.05)
})

test('failed, duplicate, missing partial and tail-regression cases fail closed', () => {
  const result = summarize([
    completed('a'.repeat(64), null, 1_000, 300, 0.01),
    completed('a'.repeat(64), 1_300, 2_500, 1_700, 0.35),
    { schemaVersion: 1, status: 'failed' },
  ], { ...limits, minimumCases: 3 })
  assert.equal(result.status, 'rejected')
  for (const violation of [
    'minimum_unique_sources', 'failed_cases', 'first_partial_p95',
    'vad_end_to_final_p95', 'mean_cer', 'worst_cer', 'partial_coverage',
  ]) assert.ok(result.violations.includes(violation), violation)
})
