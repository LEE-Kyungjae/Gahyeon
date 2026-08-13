export class SpeechRequestRegistry {
  private readonly requests = new Map<number, Set<AbortController>>()

  begin(senderId: number) {
    const controller = new AbortController()
    const active = this.requests.get(senderId) ?? new Set<AbortController>()
    active.add(controller)
    this.requests.set(senderId, active)
    let completed = false
    return {
      signal: controller.signal,
      complete: () => {
        if (completed) return
        completed = true
        active.delete(controller)
        if (active.size === 0) this.requests.delete(senderId)
      },
    }
  }

  cancel(senderId: number) {
    const active = this.requests.get(senderId)
    active?.forEach(controller => controller.abort())
    this.requests.delete(senderId)
    return active?.size ?? 0
  }

  cancelAll() {
    for (const senderId of this.requests.keys()) this.cancel(senderId)
  }

  activeCount(senderId: number) {
    return this.requests.get(senderId)?.size ?? 0
  }
}
