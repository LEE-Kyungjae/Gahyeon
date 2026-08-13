import { describe, expect, it } from 'vitest'
import {
  selectApprovedHeroPackage,
  readExactPackage,
  verifyHeroPackageBytes,
} from './hero-asset-loader'
import type { GahyeonHeroAssetManifest } from './hero-asset-manifest'

const packageBytes = new TextEncoder().encode('verified-gahyeon-vrm')
const packageSha = '404a4a34d5863e1423ccaafbee1f98c2ccb8d5f152698d84c68b1359bb0836e6'

function manifest(status: 'draft' | 'approved' = 'approved'): GahyeonHeroAssetManifest {
  return {
    schemaVersion: 2,
    characterId: 'gahyeon',
    status,
    qualityTier: 'vrm-compatibility',
    gate: status === 'approved' ? 'G5' : 'G1',
    source: { revision: 'fixture', coordinateSystem: 'y-up-right-handed', unitMeters: 1 },
    packages: [{
      renderer: 'three-vrm', format: 'vrm', uri: './gahyeon.vrm', sha256: packageSha,
      bytes: packageBytes.byteLength, lods: ['LOD0'], materials: ['skin'], groomMode: 'cards',
    }],
    semantics: {
      expressions: ['neutral'],
      visemes: ['sil', 'aa', 'ih', 'ou', 'ee', 'oh', 'fv', 'l', 'mbp', 'wq'],
      activities: ['idle'],
    },
    provenance: {
      author: 'fixture', license: 'private', sourceFiles: ['source.blend'],
      sourceManifests: [
        { kind: 'identity-reference', uri: 'identity.json', sha256: '1'.repeat(64) },
        { kind: 'modeling-input', uri: 'modeling.json', sha256: '2'.repeat(64) },
      ],
      createdAt: '2026-08-12T00:00:00Z', approvedAt: '2026-08-12T00:00:00Z',
      approvedBy: 'owner',
    },
    acceptanceEvidence: ['G1', 'G2', 'G3', 'G4', 'G5'].map((gate, index) => ({
      gate: gate as 'G1' | 'G2' | 'G3' | 'G4' | 'G5',
      uri: `${gate}.json`, sha256: String(index + 3).repeat(64),
    })),
  }
}

describe('approved Hero package loader', () => {
  it('selects only an approved renderer package', () => {
    expect(selectApprovedHeroPackage(manifest(), 'three-vrm').uri).toBe('./gahyeon.vrm')
    expect(() => selectApprovedHeroPackage(manifest('draft'), 'three-vrm'))
      .toThrow(/not G5-approved/)
    const ambiguous = manifest()
    ambiguous.packages.push({ ...ambiguous.packages[0], uri: './other.vrm' })
    expect(() => selectApprovedHeroPackage(ambiguous, 'three-vrm'))
      .toThrow(/exactly one three-vrm/)
  })

  it('verifies exact bytes and SHA before renderer loading', async () => {
    await expect(verifyHeroPackageBytes(
      packageBytes, selectApprovedHeroPackage(manifest(), 'three-vrm'),
    )).resolves.toBeUndefined()
    await expect(verifyHeroPackageBytes(
      new TextEncoder().encode('tampered-gahyeon-vrm'),
      selectApprovedHeroPackage(manifest(), 'three-vrm'),
    )).rejects.toThrow(/byte size|SHA-256/)
  })

  it('stops streamed package bytes at the manifest boundary', async () => {
    await expect(readExactPackage(new Response(packageBytes, {
      headers: { 'content-length': String(packageBytes.byteLength) },
    }), packageBytes.byteLength)).resolves.toEqual(packageBytes)
    await expect(readExactPackage(new Response(packageBytes, {
      headers: { 'content-length': String(packageBytes.byteLength + 1) },
    }), packageBytes.byteLength)).rejects.toThrow(/Content-Length/)
    await expect(readExactPackage(new Response(new Uint8Array(packageBytes.byteLength + 1)),
      packageBytes.byteLength)).rejects.toThrow(/exceeds manifest/)
  })
})
