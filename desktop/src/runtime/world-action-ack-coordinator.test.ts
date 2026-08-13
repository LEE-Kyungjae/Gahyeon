import { describe, expect, it, vi } from 'vitest'
import type { WorldActionAckCommand } from './world-action-ack-outbox'
import { WorldActionAckCoordinator } from './world-action-ack-coordinator'

const command: WorldActionAckCommand = {
  worldId: 'gahyeon-home',
  request: {
    installationId: 'install-1', actionId: 'action-1', expectedRevision: 7,
    finalPosition: { x: 7, y: 0, z: -2 },
  },
}

describe('WorldActionAckCoordinator', () => {
  it('drops renderer presence when the arrived action cannot be persisted', async () => {
    const worker = { enqueue: vi.fn().mockReturnValue({ accepted: false }),
      start: vi.fn(), stop: vi.fn(), reconcileActionResult: vi.fn() }
    const presence = { start: vi.fn(), stop: vi.fn().mockResolvedValue(undefined) }
    const coordinator = new WorldActionAckCoordinator(worker, presence)

    expect(coordinator.enqueue(command)).toBe(false)
    expect(presence.stop).toHaveBeenCalledTimes(1)
    expect(worker.stop).toHaveBeenCalledTimes(1)
    expect(coordinator.rendererAvailable()).toBe(false)
  })

  it('restores presence after the failed action is authoritatively committed', async () => {
    const worker = { enqueue: vi.fn().mockReturnValue({ accepted: false }),
      start: vi.fn(), stop: vi.fn(), reconcileActionResult: vi.fn()
        .mockReturnValue({ removed: false, status: 'missing' }) }
    const presence = { start: vi.fn(), stop: vi.fn().mockResolvedValue(undefined) }
    const coordinator = new WorldActionAckCoordinator(worker, presence)
    coordinator.setRendererPresent(true)
    coordinator.enqueue(command)

    coordinator.reconcile('unrelated-action')
    expect(presence.start).toHaveBeenCalledTimes(1)
    coordinator.reconcile('action-1')

    expect(presence.start).toHaveBeenCalledTimes(2)
    expect(worker.start).toHaveBeenCalledTimes(2)
    expect(coordinator.rendererAvailable()).toBe(true)
  })

  it('restarts the ACK worker when a renderer remounts after fallback completed', () => {
    const worker = { enqueue: vi.fn().mockReturnValue({ accepted: false }),
      start: vi.fn(), stop: vi.fn(), reconcileActionResult: vi.fn() }
    const presence = { start: vi.fn(), stop: vi.fn().mockResolvedValue(undefined) }
    const coordinator = new WorldActionAckCoordinator(worker, presence)
    coordinator.setRendererPresent(true)
    coordinator.enqueue(command)
    coordinator.setRendererPresent(false)
    coordinator.reconcile('action-1')

    coordinator.setRendererPresent(true)

    expect(worker.start).toHaveBeenCalledTimes(2)
    expect(presence.start).toHaveBeenCalledTimes(2)
  })

  it('recovers immediately when a remount can persist the previously blocked action', () => {
    const worker = { enqueue: vi.fn()
      .mockReturnValueOnce({ accepted: false })
      .mockReturnValueOnce({ accepted: true }),
    start: vi.fn(), stop: vi.fn(), reconcileActionResult: vi.fn() }
    const presence = { start: vi.fn(), stop: vi.fn().mockResolvedValue(undefined) }
    const coordinator = new WorldActionAckCoordinator(worker, presence)
    coordinator.setRendererPresent(true)
    coordinator.enqueue(command)

    expect(coordinator.enqueue(command)).toBe(true)

    expect(worker.start).toHaveBeenCalledTimes(2)
    expect(presence.start).toHaveBeenCalledTimes(2)
    expect(coordinator.rendererAvailable()).toBe(true)
  })

  it('permanently drops presence for the process after durable storage becomes unhealthy', () => {
    let failDurability!: () => void
    const worker = { enqueue: vi.fn().mockReturnValue({ accepted: true }),
      start: vi.fn(), stop: vi.fn(), reconcileActionResult: vi.fn(),
      onDurabilityFailure: vi.fn((listener: () => void) => {
        failDurability = listener
        return () => undefined
      }) }
    const presence = { start: vi.fn(), stop: vi.fn().mockResolvedValue(undefined) }
    const coordinator = new WorldActionAckCoordinator(worker, presence)
    coordinator.setRendererPresent(true)

    failDurability()
    coordinator.setRendererPresent(true)
    coordinator.reconcile('action-1')

    expect(worker.stop).toHaveBeenCalledTimes(1)
    expect(presence.stop).toHaveBeenCalledTimes(1)
    expect(coordinator.rendererAvailable()).toBe(false)
    expect(presence.start).toHaveBeenCalledTimes(1)
  })

  it('accepts an external durable journal failure as a permanent fail-closed signal', () => {
    const worker = { enqueue: vi.fn().mockReturnValue({ accepted: true }),
      start: vi.fn(), stop: vi.fn(), reconcileActionResult: vi.fn() }
    const presence = { start: vi.fn(), stop: vi.fn().mockResolvedValue(undefined) }
    const coordinator = new WorldActionAckCoordinator(worker, presence)
    coordinator.setRendererPresent(true)

    coordinator.failDurability()
    coordinator.setRendererPresent(true)

    expect(coordinator.rendererAvailable()).toBe(false)
    expect(worker.stop).toHaveBeenCalledTimes(1)
    expect(presence.stop).toHaveBeenCalledTimes(1)
  })
})
