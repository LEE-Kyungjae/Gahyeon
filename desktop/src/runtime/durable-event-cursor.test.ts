import { describe, expect, it } from 'vitest'
import { DurableEventCursor } from './durable-event-cursor'

describe('DurableEventCursor', () => {
  it('admits each increasing durable event exactly once', () => {
    const cursor = new DurableEventCursor('7')
    const prepared = cursor.prepare({ id: '8' })
    expect(prepared).toEqual({ durable: true, cursor: 8 })
    expect(cursor.current()).toBe(7)
    expect(cursor.commit(prepared!)).toBe(true)
    expect(cursor.prepare({ id: '8' })).toBeUndefined()
    expect(cursor.prepare({ id: '6' })).toBeUndefined()
    expect(cursor.current()).toBe(8)
  })

  it('allows ephemeral events without moving the durable cursor', () => {
    const cursor = new DurableEventCursor('4')
    const prepared = cursor.prepare({})
    expect(prepared).toEqual({ durable: false, cursor: 4 })
    expect(cursor.commit(prepared!)).toBe(false)
    expect(cursor.current()).toBe(4)
  })

  it('fails closed for corrupted persisted and incoming sequence values', () => {
    const cursor = new DurableEventCursor('NaN')
    expect(cursor.current()).toBe(0)
    expect(cursor.prepare({ id: '-1' })).toBeUndefined()
    expect(cursor.prepare({ id: '1.5' })).toBeUndefined()
    expect(cursor.prepare({ id: '9007199254740992' })).toBeUndefined()
    expect(cursor.current()).toBe(0)
  })

  it('does not advance when the caller fails before commit', () => {
    const cursor = new DurableEventCursor('10')
    expect(cursor.prepare({ id: '11' })).toBeDefined()
    expect(cursor.current()).toBe(10)
    expect(cursor.prepare({ id: '11' })).toBeDefined()
  })
})
