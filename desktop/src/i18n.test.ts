import { afterEach, describe, expect, it } from 'vitest'
import { locale, setLocale, t, type Locale } from './i18n'

describe('desktop i18n contract', () => {
  afterEach(() => setLocale('en'))

  it('provides the central UI contract in all supported locales', () => {
    const samples: Record<Locale, string> = { ko: '보내기', en: 'Send', ja: '送信' }
    for (const [next, expected] of Object.entries(samples) as Array<[Locale, string]>) {
      setLocale(next)
      expect(locale.value).toBe(next)
      expect(t('conversation.send')).toBe(expected)
      expect(t('voice.on')).not.toBe('')
      expect(t('stage.label')).toContain('Gahyeon')
      expect(t('error.heroManifest', { details: 'SHA' })).toContain('SHA')
      expect(t('identity.nativeRequired')).not.toBe('')
    }
  })
})
