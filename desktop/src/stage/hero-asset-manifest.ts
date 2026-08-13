export type HeroQualityTier =
  | 'hero-master'
  | 'desktop-performance'
  | 'looking-glass'
  | 'vrm-compatibility'

export type HeroRendererKind = 'hero-engine' | 'three-vrm' | 'looking-glass'
export type GroomMode = 'strands' | 'cards' | 'mesh' | 'none'
export type HeroAssetStatus = 'draft' | 'candidate' | 'approved' | 'retired'
export type HeroAssetGate = 'G1' | 'G2' | 'G3' | 'G4' | 'G5'

export interface HeroAssetPackage {
  renderer: HeroRendererKind
  format: 'unreal-content-zip' | 'vrm' | 'glb'
  uri: string
  sha256: string
  bytes: number
  lods: string[]
  materials: string[]
  groomMode: GroomMode
  peakVramMiB?: number
  targetFrameMs?: number
}

export interface GahyeonHeroAssetManifest {
  schemaVersion: 2
  characterId: 'gahyeon'
  status: HeroAssetStatus
  qualityTier: HeroQualityTier
  gate: HeroAssetGate
  source: {
    revision: string
    coordinateSystem: 'y-up-right-handed' | 'z-up-right-handed' | 'z-up-left-handed'
    unitMeters: number
  }
  packages: HeroAssetPackage[]
  semantics: {
    expressions: string[]
    visemes: string[]
    activities: string[]
  }
  provenance: {
    author: string
    license: string
    sourceFiles: string[]
    sourceManifests: Array<{
      kind: 'identity-reference' | 'modeling-input'
      uri: string
      sha256: string
    }>
    createdAt: string
    approvedAt?: string
    approvedBy?: string
  }
  acceptanceEvidence: Array<{ gate: HeroAssetGate; uri: string; sha256: string }>
}

const sha256 = /^[a-f0-9]{64}$/
const requiredVisemes = ['sil', 'aa', 'ih', 'ou', 'ee', 'oh', 'fv', 'l', 'mbp', 'wq']
const statuses = new Set<unknown>(['draft', 'candidate', 'approved', 'retired'])
const gates = new Set<unknown>(['G1', 'G2', 'G3', 'G4', 'G5'])
const renderers = new Set<unknown>(['hero-engine', 'three-vrm', 'looking-glass'])

export function validateHeroAssetManifest(value: unknown): GahyeonHeroAssetManifest {
  if (!record(value)) throw new Error('Hero asset manifest must be an object.')
  if (value.schemaVersion !== 2) throw new Error('Unsupported hero asset schema version.')
  if (value.characterId !== 'gahyeon') throw new Error('Hero asset must target Gahyeon.')
  if (!statuses.has(value.status)) throw new Error('Hero asset has an invalid lifecycle status.')
  if (!gates.has(value.gate)) throw new Error('Hero asset has an invalid production gate.')
  if (!Array.isArray(value.packages) || value.packages.length === 0) {
    throw new Error('Hero asset requires at least one renderer package.')
  }
  for (const item of value.packages) {
    if (!record(item) || typeof item.uri !== 'string' || !item.uri.trim()) {
      throw new Error('Every hero renderer package requires a URI.')
    }
    if (!renderers.has(item.renderer)) throw new Error('Hero asset package has an invalid renderer.')
    const validFormat = (item.renderer === 'hero-engine' && item.format === 'unreal-content-zip')
      || (item.renderer === 'three-vrm' && item.format === 'vrm')
      || (item.renderer === 'looking-glass' && ['vrm', 'glb'].includes(String(item.format)))
    if (!validFormat) throw new Error('Hero asset package format does not match its renderer.')
    if (typeof item.sha256 !== 'string' || !sha256.test(item.sha256)) {
      throw new Error('Every hero renderer package requires a SHA-256 checksum.')
    }
    if (!Number.isSafeInteger(item.bytes) || Number(item.bytes) <= 0) {
      throw new Error('Every hero renderer package requires a positive byte size.')
    }
    if (!nonEmptyStrings(item.lods) || !nonEmptyStrings(item.materials)) {
      throw new Error('Every hero renderer package requires LOD and material names.')
    }
  }
  const semantics = value.semantics
  if (!record(semantics) || !nonEmptyStrings(semantics.visemes)) {
    throw new Error('Hero asset requires viseme semantics.')
  }
  const visemes = semantics.visemes
  const missing = requiredVisemes.filter(viseme => !visemes.includes(viseme))
  if (missing.length) throw new Error(`Hero asset is missing required visemes: ${missing.join(', ')}`)
  const provenance = value.provenance
  if (!record(provenance) || !nonEmptyStrings(provenance.sourceFiles)) {
    throw new Error('Hero asset requires source provenance.')
  }
  if (!Array.isArray(provenance.sourceManifests) || provenance.sourceManifests.length !== 2
      || provenance.sourceManifests.some(item => !record(item)
        || !['identity-reference', 'modeling-input'].includes(String(item.kind))
        || typeof item.uri !== 'string' || !item.uri.trim()
        || typeof item.sha256 !== 'string' || !sha256.test(item.sha256))) {
    throw new Error('Hero asset must bind identity and modeling source manifests.')
  }
  const sourceKinds = new Set(provenance.sourceManifests.map(item => item.kind))
  if (sourceKinds.size !== 2) {
    throw new Error('Hero asset must bind one identity and one modeling source manifest.')
  }
  if (!Array.isArray(value.acceptanceEvidence)) {
    throw new Error('Hero asset requires an acceptance evidence list.')
  }
  if (value.status === 'approved') validateApproval(value, provenance)
  return value as unknown as GahyeonHeroAssetManifest
}

function validateApproval(value: Record<string, unknown>, provenance: Record<string, unknown>): void {
  if (value.gate !== 'G5' || typeof provenance.approvedAt !== 'string'
      || !provenance.approvedAt.trim() || typeof provenance.approvedBy !== 'string'
      || !provenance.approvedBy.trim()) {
    throw new Error('Approved hero assets require G5 and explicit operator approval.')
  }
  const evidence = value.acceptanceEvidence as unknown[]
  const gates = new Set(evidence.map(item => record(item) ? item.gate : undefined))
  const missing = ['G1', 'G2', 'G3', 'G4', 'G5'].filter(gate => !gates.has(gate))
  if (missing.length || evidence.length !== 5 || gates.size !== 5) {
    throw new Error(`Approved hero asset requires exactly one G1-G5 evidence item${missing.length ? `; missing: ${missing.join(', ')}` : ''}`)
  }
  for (const item of evidence) {
    if (!record(item) || typeof item.uri !== 'string' || !item.uri.trim()
        || typeof item.sha256 !== 'string' || !sha256.test(item.sha256)) {
      throw new Error('Hero asset gate evidence requires URI and SHA-256.')
    }
  }
  const packages = value.packages as Array<Record<string, unknown>>
  if (packages.some(item => item.sha256 === '0'.repeat(64))) {
    throw new Error('Approved hero asset cannot use placeholder package checksums.')
  }
}

function record(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function nonEmptyStrings(value: unknown): value is string[] {
  return Array.isArray(value) && value.length > 0
    && value.every(item => typeof item === 'string' && item.trim().length > 0)
}
