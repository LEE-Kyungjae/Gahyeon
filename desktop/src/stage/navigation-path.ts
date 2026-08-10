import type { Vector3State } from './stage-state'

const doorways: Record<string, Vector3State[]> = {
  'bedroom>living_room': [{ x: 0, y: 0, z: -3.5 }],
  'living_room>bedroom': [{ x: 0, y: 0, z: -3.5 }],
  'living_room>workspace': [
    { x: 3.8, y: 0, z: -4.2 },
    { x: 5.1, y: 0, z: -2.5 },
  ],
  'workspace>living_room': [
    { x: 5.1, y: 0, z: -2.5 },
    { x: 3.8, y: 0, z: -4.2 },
  ],
  'bedroom>workspace': [
    { x: 2.7, y: 0, z: -2.3 },
    { x: 5.1, y: 0, z: -2.5 },
  ],
  'workspace>bedroom': [
    { x: 5.1, y: 0, z: -2.5 },
    { x: 2.7, y: 0, z: -2.3 },
  ],
}

export function buildNavigationPath(
  fromRoom: string,
  toRoom: string,
  destination: Vector3State,
): Vector3State[] {
  const route = doorways[`${fromRoom}>${toRoom}`] ?? []
  return [...route, destination].map(point => ({ ...point }))
}
