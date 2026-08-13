import { describe, expect, it } from 'vitest'
import { appendBoundedResponseText } from './bounded-response-text'

describe('appendBoundedResponseText', () => {
  it('accepts the exact response boundary', () => {
    expect(appendBoundedResponseText('가'.repeat(7), '나', 8)).toBe('가'.repeat(7) + '나')
  })

  it('rejects before allocating a string beyond the hard boundary', () => {
    expect(() => appendBoundedResponseText('가'.repeat(8), '나', 8))
      .toThrow('renderer capacity')
  })

  it('rejects unsafe configuration', () => {
    expect(() => appendBoundedResponseText('', '', 0)).toThrow()
  })
})
