import { describe, expect, it } from 'vitest'
import {
  isUnsuccessfulConversationTerminal,
  parseConversationTerminal,
  reconcileConversationTerminal,
} from './terminal-event'

describe('parseConversationTerminal', () => {
  it('classifies only failed and cancelled terminals as unsuccessful', () => {
    expect(isUnsuccessfulConversationTerminal('conversation.failed')).toBe(true)
    expect(isUnsuccessfulConversationTerminal('conversation.cancelled')).toBe(true)
    expect(isUnsuccessfulConversationTerminal('conversation.completed')).toBe(false)
    expect(isUnsuccessfulConversationTerminal('conversation.delta')).toBe(false)
  })

  it('extracts request identity and final content from a durable Core event', () => {
    expect(parseConversationTerminal('conversation.completed', {
      correlationId: 'request-1',
      payload: { runId: 'run-7', content: '최종 응답', tools: [] },
    })).toEqual({
      type: 'conversation.completed',
      requestId: 'request-1',
      runId: 'run-7',
      content: '최종 응답',
    })
  })

  it('accepts failed and cancelled terminals without exposing raw error payloads', () => {
    expect(parseConversationTerminal('conversation.failed', {
      correlationId: 'failed-1', payload: { message: 'secret' },
    })).toEqual({
      type: 'conversation.failed', requestId: 'failed-1',
      runId: undefined, content: undefined,
    })
    expect(parseConversationTerminal('conversation.cancelled', {
      correlationId: 'cancelled-1', payload: {},
    })?.requestId).toBe('cancelled-1')
  })

  it('fails closed for unknown types and missing correlation identity', () => {
    expect(parseConversationTerminal('conversation.delta', {
      correlationId: 'request-1', payload: {},
    })).toBeUndefined()
    expect(parseConversationTerminal('conversation.completed', {
      payload: { content: 'orphan' },
    })).toBeUndefined()
  })

  it('closes an orphan stream but leaves a local POST as terminal owner', () => {
    const orphan = [{ id: 'stream:request-1', requestId: 'request-1', text: '부분' }]
    const terminal = parseConversationTerminal('conversation.completed', {
      correlationId: 'request-1', payload: { runId: 'run-1', content: '최종' },
    })!
    expect(reconcileConversationTerminal(orphan, false, terminal)).toBeUndefined()
    expect(orphan).toEqual([{ id: 'run-1', text: '최종' }])

    const local = [{ id: 'stream:request-1', requestId: 'request-1', text: '부분' }]
    reconcileConversationTerminal(local, true, terminal)
    expect(local).toEqual([{ id: 'run-1', requestId: 'request-1', text: '최종' }])
  })

  it('releases a partial message when an unsuccessful terminal owns completion', () => {
    const messages = [{ id: 'stream:failed', requestId: 'failed', text: '부분 응답' }]
    const terminal = parseConversationTerminal('conversation.failed', {
      correlationId: 'failed', payload: {},
    })!
    reconcileConversationTerminal(messages, false, terminal)
    expect(messages).toEqual([{ id: 'stream:failed', text: '부분 응답' }])
  })

  it('returns one final message when a completion replays without prior deltas', () => {
    const terminal = parseConversationTerminal('conversation.completed', {
      correlationId: 'request-2', payload: { runId: 'run-2', content: '복원된 응답' },
    })!
    expect(reconcileConversationTerminal([], false, terminal))
      .toEqual({ id: 'run-2', text: '복원된 응답' })
    expect(reconcileConversationTerminal([], true, terminal)).toBeUndefined()
  })

  it('does not duplicate an HTTP result after a long SSE outage expires request TTL', () => {
    const messages = [{ id: 'run-existing', text: '이미 확정된 응답' }]
    const terminal = parseConversationTerminal('conversation.completed', {
      correlationId: 'expired-request-id',
      payload: { runId: 'run-existing', content: '이미 확정된 응답' },
    })!
    expect(reconcileConversationTerminal(messages, false, terminal)).toBeUndefined()
    expect(messages).toHaveLength(1)
  })
})
