declare module '@lookingglass/webxr' {
  export interface LookingGlassViewConfig {
    tileHeight: number
    numViews: number
    targetX: number
    targetY: number
    targetZ: number
    targetDiam: number
    trackballX: number
    trackballY: number
    fovy: number
    depthiness: number
    inlineView: number
  }

  export class LookingGlassWebXRPolyfill {
    constructor(config?: Partial<LookingGlassViewConfig>)
    isPresenting: boolean
    update(config: Partial<LookingGlassViewConfig>): void
  }
}
