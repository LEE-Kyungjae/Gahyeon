import { describe, expect, it } from 'vitest'
import { IncrementalSentenceAccumulator, unseenFinalText } from './incremental-sentence-accumulator'

describe('IncrementalSentenceAccumulator', () => {
  it('emits complete sentences before cognition finishes', () => {
    const accumulator = new IncrementalSentenceAccumulator()
    expect(accumulator.accept('안녕하')).toEqual([])
    expect(accumulator.accept('세요. 다음')).toEqual(['안녕하세요.'])
    expect(accumulator.finish()).toEqual(['다음'])
  })

  it('hard-bounds a long response even when it has no punctuation or whitespace', () => {
    const accumulator = new IncrementalSentenceAccumulator(20)
    expect(accumulator.accept('가'.repeat(20))).toEqual(['가'.repeat(20)])
    expect(accumulator.accept('나'.repeat(41))).toEqual([
      '나'.repeat(20),
      '나'.repeat(20),
    ])
    expect(accumulator.finish()).toEqual(['나'])
  })

  it('never splits a surrogate pair at the hard boundary', () => {
    const accumulator = new IncrementalSentenceAccumulator(20)
    expect(accumulator.accept(`${'가'.repeat(19)}😀나`)).toEqual([
      `${'가'.repeat(19)}😀`,
    ])
    expect(accumulator.finish()).toEqual(['나'])
  })

  it('flushes only the final suffix not yet delivered by SSE', () => {
    expect(unseenFinalText('첫 문장. 둘째 문장.', '첫 문장. 둘째')).toBe(' 문장.')
    expect(unseenFinalText('교정된 최종 답변', '서로 다른 초안')).toBe('')
  })
})
