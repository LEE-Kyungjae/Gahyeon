import { describe, expect, it } from 'vitest'
import { validateHeroAssetManifest } from './hero-asset-manifest'

const manifest = {
  schemaVersion: 2,
  characterId: 'gahyeon',
  status: 'approved',
  qualityTier: 'hero-master',
  gate: 'G5',
  source: { revision: 'hero-v1', coordinateSystem: 'z-up-left-handed', unitMeters: 1 },
  packages: [{
    renderer: 'hero-engine',
    format: 'unreal-content-zip',
    uri: 'character/gahyeon.uasset',
    sha256: '0'.repeat(64),
    bytes: 1024,
    lods: ['LOD0'],
    materials: ['skin'],
    groomMode: 'strands',
  }],
  semantics: {
    expressions: ['neutral'],
    visemes: ['sil', 'aa', 'ih', 'ou', 'ee', 'oh', 'fv', 'l', 'mbp', 'wq'],
    activities: ['idle'],
  },
  provenance: {
    author: 'Gahyeon project',
    license: 'private',
    sourceFiles: ['gahyeon.blend'],
    sourceManifests: [
      { kind: 'identity-reference', uri: 'identity-reference.json', sha256: '1'.repeat(64) },
      { kind: 'modeling-input', uri: 'modeling-input.json', sha256: '2'.repeat(64) },
    ],
    createdAt: '2026-08-11T00:00:00Z',
    approvedAt: '2026-08-11T00:00:00Z',
    approvedBy: 'owner',
  },
  acceptanceEvidence: ['G1', 'G2', 'G3', 'G4', 'G5'].map((gate, index) => ({
    gate, uri: `evidence/${gate}.json`, sha256: String(index + 3).repeat(64),
  })),
}

describe('hero asset manifest', () => {
  it('accepts an approved Gahyeon hero package', () => {
      const approved = structuredClone(manifest)
      approved.packages[0].sha256 = 'a'.repeat(64)
      expect(validateHeroAssetManifest(approved).characterId).toBe('gahyeon')
  })

  it('rejects a package without reproducible weights', () => {
    const invalid = structuredClone(manifest)
    invalid.packages[0].sha256 = 'missing'
    expect(() => validateHeroAssetManifest(invalid)).toThrow(/SHA-256/)
  })

  it('rejects a facial rig without Korean viseme semantics', () => {
    const invalid = structuredClone(manifest)
    invalid.semantics.visemes = ['sil', 'aa']
    expect(() => validateHeroAssetManifest(invalid)).toThrow(/missing required visemes/)
  })

  it('allows a draft without fake approval evidence', () => {
    const draft = structuredClone(manifest)
    draft.status = 'draft'
    draft.gate = 'G1'
    delete (draft.provenance as Record<string, unknown>).approvedAt
    delete (draft.provenance as Record<string, unknown>).approvedBy
    draft.acceptanceEvidence = []
    expect(validateHeroAssetManifest(draft).status).toBe('draft')
  })

  it('rejects approval before all G1 through G5 evidence is sealed', () => {
    const invalid = structuredClone(manifest)
    invalid.packages[0].sha256 = 'a'.repeat(64)
    invalid.acceptanceEvidence = invalid.acceptanceEvidence.slice(0, 4)
    expect(() => validateHeroAssetManifest(invalid)).toThrow(/exactly one G1-G5/)
  })

  it('rejects invented lifecycle states even for drafts', () => {
    const invalid = structuredClone(manifest)
    invalid.status = 'shipped'
    expect(() => validateHeroAssetManifest(invalid)).toThrow(/invalid lifecycle status/)
  })

  it('rejects duplicate source-manifest roles', () => {
    const invalid = structuredClone(manifest)
    invalid.provenance.sourceManifests[1].kind = 'identity-reference'
    expect(() => validateHeroAssetManifest(invalid)).toThrow(/one identity and one modeling/)
  })
})
