import { GahyeonClientError } from '../client-error'
import {
  validateHeroAssetManifest,
  type GahyeonHeroAssetManifest,
  type HeroAssetPackage,
  type HeroRendererKind,
} from './hero-asset-manifest'

const maximumPackageBytes = 512 * 1024 * 1024

export function selectApprovedHeroPackage(
  manifest: GahyeonHeroAssetManifest,
  renderer: HeroRendererKind,
): HeroAssetPackage {
  if (manifest.status !== 'approved' || manifest.gate !== 'G5') {
    throw new GahyeonClientError('heroManifest', 'asset is not G5-approved')
  }
  const matches = manifest.packages.filter(candidate => candidate.renderer === renderer)
  if (matches.length !== 1) {
    throw new GahyeonClientError('heroManifest', `requires exactly one ${renderer} package`)
  }
  const selected = matches[0]
  if (renderer === 'three-vrm' && selected.format !== 'vrm') {
    throw new GahyeonClientError('heroManifest', 'Desktop package is not VRM')
  }
  if (selected.bytes > maximumPackageBytes) {
    throw new GahyeonClientError('heroManifest', 'package exceeds 512 MiB desktop limit')
  }
  return selected
}

export async function verifyHeroPackageBytes(
  bytes: Uint8Array,
  expected: HeroAssetPackage,
): Promise<void> {
  if (bytes.byteLength !== expected.bytes) {
    throw new GahyeonClientError('heroManifest', 'package byte size does not match manifest')
  }
  const buffer = Uint8Array.from(bytes).buffer
  const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', buffer))]
    .map(value => value.toString(16).padStart(2, '0')).join('')
  if (digest !== expected.sha256) {
    throw new GahyeonClientError('heroManifest', 'package SHA-256 does not match manifest')
  }
}

export async function readExactPackage(response: Response, expectedBytes: number): Promise<Uint8Array> {
  const declared = response.headers.get('content-length')
  if (declared !== null && Number(declared) !== expectedBytes) {
    throw new GahyeonClientError('heroManifest', 'package Content-Length does not match manifest')
  }
  if (!response.body) throw new GahyeonClientError('heroManifest', 'package response has no body')
  const reader = response.body.getReader()
  const chunks: Uint8Array[] = []
  let total = 0
  try {
    while (true) {
      const next = await reader.read()
      if (next.done) break
      total += next.value.byteLength
      if (total > expectedBytes) {
        await reader.cancel('package exceeds manifest byte size')
        throw new GahyeonClientError('heroManifest', 'package exceeds manifest byte size')
      }
      chunks.push(next.value)
    }
  }
  finally {
    reader.releaseLock()
  }
  if (total !== expectedBytes) {
    throw new GahyeonClientError('heroManifest', 'package byte size does not match manifest')
  }
  const bytes = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    bytes.set(chunk, offset)
    offset += chunk.byteLength
  }
  return bytes
}

export async function loadApprovedHeroPackage(
  manifestUrl: string,
  renderer: HeroRendererKind,
): Promise<{ objectUrl: string; revoke(): void }> {
  const manifestResponse = await fetch(manifestUrl, { cache: 'no-store' })
  if (!manifestResponse.ok) {
    throw new GahyeonClientError('heroManifest', `manifest HTTP ${manifestResponse.status}`)
  }
  let manifest: GahyeonHeroAssetManifest
  try {
    manifest = validateHeroAssetManifest(await manifestResponse.json())
  }
  catch (error) {
    throw new GahyeonClientError(
      'heroManifest', error instanceof Error ? error.message : String(error),
    )
  }
  const selected = selectApprovedHeroPackage(manifest, renderer)
  const packageUrl = new URL(selected.uri, manifestUrl).toString()
  const packageResponse = await fetch(packageUrl, { cache: 'no-store' })
  if (!packageResponse.ok) {
    throw new GahyeonClientError('heroManifest', `package HTTP ${packageResponse.status}`)
  }
  const bytes = await readExactPackage(packageResponse, selected.bytes)
  await verifyHeroPackageBytes(bytes, selected)
  const objectUrl = URL.createObjectURL(new Blob([Uint8Array.from(bytes).buffer]))
  return { objectUrl, revoke: () => URL.revokeObjectURL(objectUrl) }
}
