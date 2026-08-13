import assert from 'node:assert/strict'
import test from 'node:test'

import { parseSuite } from './run-streaming-stt-suite.mjs'

test('suite paths resolve relative to the suite and repeats remain bounded', () => {
  const cases = parseSuite([
    JSON.stringify({ id: 'neutral-01', wav: 'wav/01.wav', expected: '안녕하세요.', repeats: 2 }),
    JSON.stringify({ id: 'question-01', wav: 'wav/02.wav', expected: '지금 실행할까요?', repeats: 1 }),
  ].join('\n'), '/evaluation')
  assert.equal(cases.length, 2)
  assert.equal(cases[0].wav, '/evaluation/wav/01.wav')
  assert.equal(cases.reduce((sum, item) => sum + item.repeats, 0), 3)
})

test('suite rejects unknown fields, duplicate ids and unsafe trial counts', () => {
  assert.throws(() => parseSuite(
    JSON.stringify({ id: 'a', wav: 'a.wav', expected: '가', repeats: 1, extra: true }), '/tmp'),
  /exactly/)
  const duplicate = JSON.stringify({ id: 'a', wav: 'a.wav', expected: '가', repeats: 1 })
  assert.throws(() => parseSuite(`${duplicate}\n${duplicate}`, '/tmp'), /duplicate/)
  assert.throws(() => parseSuite(
    JSON.stringify({ id: '../bad', wav: 'a.wav', expected: '가', repeats: 1 }), '/tmp'), /invalid/)
  assert.throws(() => parseSuite(
    JSON.stringify({ id: 'a', wav: 'a.wav', expected: '가', repeats: 21 }), '/tmp'), /invalid/)
})
