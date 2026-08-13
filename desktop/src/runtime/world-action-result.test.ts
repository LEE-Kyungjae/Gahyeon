import { describe, expect, it } from 'vitest'
import { terminalActionResultId } from './world-action-result'

describe('world action result reconciliation', () => {
  const event = (result: unknown) => ({
    scope: { type: 'WORLD', id: 'gahyeon-home' },
    payload: { actionId: 'action-1', result },
  })

  it.each(['committed', 'duplicate', 'recorded_failure', 'conflict'])(
    'accepts authoritative terminal result %s', result => {
      expect(terminalActionResultId(event(result))).toBe('action-1')
    },
  )

  it('rejects malformed or non-terminal result payloads', () => {
    expect(terminalActionResultId(event('stale'))).toBe('')
    expect(terminalActionResultId(event('invalid'))).toBe('')
    expect(terminalActionResultId({ payload: { actionId: 'action-1' } })).toBe('')
  })
})
