import { afterEach, describe, expect, it } from 'vitest'
import { GahyeonClientError, localizedError } from './client-error'
import { setLocale } from './i18n'

describe('localizedError', () => {
  afterEach(() => setLocale('en'))

  it('renders stable transport codes in Korean, English, and Japanese', () => {
    const error = new GahyeonClientError('conversation', '503')
    setLocale('ko')
    expect(localizedError(error)).toBe('대화 요청에 실패했습니다. (503)')
    setLocale('en')
    expect(localizedError(error)).toBe('The conversation request failed. (503)')
    setLocale('ja')
    expect(localizedError(error)).toBe('会話リクエストに失敗しました。(503)')
  })

  it('extracts codes wrapped by Electron IPC', () => {
    setLocale('en')
    expect(localizedError(new Error(
      'Error invoking remote method: Error: GAHYEON_CLIENT_ERROR:synthesis:502',
    ))).toBe('Speech synthesis failed. (502)')
  })

  it('renders the bounded response failure without leaking implementation details', () => {
    setLocale('ko')
    expect(localizedError(new GahyeonClientError('responseLimit')))
      .toBe('응답이 너무 길어 안전하게 중단했습니다.')
  })
})
