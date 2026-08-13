export class IncrementalSentenceAccumulator {
  private pending = ''

  constructor(private readonly maxCharacters = 180) {
    if (maxCharacters < 20) throw new Error('maxCharacters must be at least 20')
  }

  accept(delta: string) {
    if (!delta) return []
    this.pending += delta
    return this.drain(false)
  }

  finish() {
    return this.drain(true)
  }

  private drain(finish: boolean) {
    const ready: string[] = []
    let start = 0
    for (let index = 0; index < this.pending.length; index++) {
      const value = this.pending[index]
      const length = index - start + 1
      const sentenceBoundary = /[.?!。？！\n]/u.test(value)
      const hardBoundary = length >= this.maxCharacters && !isHighSurrogate(value)
      if (sentenceBoundary || hardBoundary) {
        this.add(ready, this.pending.slice(start, index + 1))
        start = index + 1
      }
    }
    if (start > 0) this.pending = this.pending.slice(start)
    if (finish && this.pending) {
      this.add(ready, this.pending)
      this.pending = ''
    }
    return ready
  }

  private add(target: string[], value: string) {
    const normalized = value.trim()
    if (normalized) target.push(normalized)
  }
}

function isHighSurrogate(value: string) {
  if (!value) return false
  const code = value.charCodeAt(0)
  return code >= 0xD800 && code <= 0xDBFF
}

export function unseenFinalText(finalText: string, streamedText: string) {
  return finalText.startsWith(streamedText) ? finalText.slice(streamedText.length) : ''
}
