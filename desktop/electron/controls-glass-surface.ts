import type { Rectangle } from 'electron'

export const CONTROLS_GLASS_WIDTH = 44
export const CONTROLS_GLASS_HEIGHT = 128
const CONTROLS_RIGHT = 18
const CONTROLS_BOTTOM = 12
const SUMMARY_HEIGHT = 44
const PANEL_GAP = 8

export function controlsGlassBounds(owner: Rectangle): Rectangle {
  return {
    x: owner.x + owner.width - CONTROLS_RIGHT - CONTROLS_GLASS_WIDTH,
    y: owner.y + owner.height
      - CONTROLS_BOTTOM
      - SUMMARY_HEIGHT
      - PANEL_GAP
      - CONTROLS_GLASS_HEIGHT,
    width: CONTROLS_GLASS_WIDTH,
    height: CONTROLS_GLASS_HEIGHT,
  }
}

export function roundedCapsuleShape(width: number, height: number): Rectangle[] {
  const radius = Math.floor(width / 2)
  const rows: Rectangle[] = []
  for (let y = 0; y < height; y++) {
    const edgeY = y < radius ? radius - y - 0.5 : y >= height - radius ? y - (height - radius) + 0.5 : 0
    const inset = edgeY > 0 ? Math.ceil(radius - Math.sqrt(Math.max(0, radius * radius - edgeY * edgeY))) : 0
    rows.push({ x: inset, y, width: width - inset * 2, height: 1 })
  }
  return rows
}
