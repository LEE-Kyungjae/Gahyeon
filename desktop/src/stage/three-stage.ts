import {
  AmbientLight,
  Color,
  DirectionalLight,
  Fog,
  GridHelper,
  PerspectiveCamera,
  Scene,
  SRGBColorSpace,
  Vector3,
  WebGLRenderer,
} from 'three'
import type { CharacterRenderer } from './character-renderer'
import { PlaceholderCharacterRenderer } from './placeholder-character'
import { buildNavigationPath } from './navigation-path'
import type { StageState } from './stage-state'
import { GahyeonHomeEnvironment } from './world-environment'

export class ThreeStage {
  private readonly scene = new Scene()
  private readonly camera = new PerspectiveCamera(34, 1, 0.1, 100)
  private readonly renderer: WebGLRenderer
  private lastFrameTime = performance.now()
  private readonly observer: ResizeObserver
  private readonly environment = new GahyeonHomeEnvironment()
  private character: CharacterRenderer = new PlaceholderCharacterRenderer()
  private state: StageState
  private room: string
  private navigationPath: Vector3[] = []
  private lookingGlassInitialized = false

  constructor(private readonly host: HTMLElement, initialState: StageState) {
    this.state = initialState
    this.room = initialState.room
    this.scene.background = new Color('#171924')
    this.scene.fog = new Fog('#171924', 9, 24)
    this.renderer = new WebGLRenderer({ antialias: true, alpha: false })
    this.renderer.outputColorSpace = SRGBColorSpace
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, 2))
    this.renderer.shadowMap.enabled = true
    this.renderer.domElement.setAttribute('aria-label', 'Gahyeon 3D world')
    this.host.append(this.renderer.domElement)

    const ambient = new AmbientLight('#c7c4dc', 1.75)
    const key = new DirectionalLight('#f5e4dc', 3.1)
    key.position.set(-3, 6, 4)
    key.castShadow = true
    const grid = new GridHelper(24, 24, '#5d596b', '#302f3c')
    grid.position.y = 0.002
    this.scene.add(ambient, key, grid, this.environment.object, this.character.object)
    this.camera.position.set(0, 2.15, 6.7)

    this.observer = new ResizeObserver(() => this.resize())
    this.observer.observe(host)
    this.resize()
    this.renderer.setAnimationLoop(this.render)
  }

  setState(state: StageState) {
    const destinationChanged = state.position.x !== this.state.position.x
      || state.position.y !== this.state.position.y
      || state.position.z !== this.state.position.z
      || state.room !== this.state.room
    if (destinationChanged) {
      this.navigationPath = buildNavigationPath(this.room, state.room, state.position)
        .map(point => new Vector3(point.x, point.y, point.z))
      this.room = state.room
    }
    this.state = state
  }

  setCharacter(character: CharacterRenderer) {
    this.scene.remove(this.character.object)
    this.character.dispose()
    this.environment.dispose()
    this.character = character
    this.scene.add(character.object)
  }

  async enableLookingGlass(buttonHost: HTMLElement) {
    if (this.lookingGlassInitialized) return
    this.lookingGlassInitialized = true
    let button: HTMLElement | undefined
    try {
      const [{ LookingGlassWebXRPolyfill }, { VRButton }] = await Promise.all([
        import('@lookingglass/webxr'),
        import('three/addons/webxr/VRButton.js'),
      ])
      this.renderer.xr.enabled = true
      button = VRButton.createButton(this.renderer)
      button.classList.add('looking-glass-xr-button')
      buttonHost.append(button)
      new LookingGlassWebXRPolyfill({
        tileHeight: 512,
        numViews: 45,
        targetY: 1.35,
        targetZ: 0,
        targetDiam: 3.2,
        fovy: 14 * Math.PI / 180,
        inlineView: 2,
      })
    }
    catch (error) {
      button?.remove()
      this.lookingGlassInitialized = false
      throw error
    }
  }

  dispose() {
    this.renderer.setAnimationLoop(null)
    this.observer.disconnect()
    this.character.dispose()
    this.renderer.dispose()
    this.renderer.domElement.remove()
  }

  private readonly render = (frameTime: number) => {
    const delta = Math.min((frameTime - this.lastFrameTime) / 1_000, 0.05)
    this.lastFrameTime = frameTime
    this.advanceNavigation(delta)
    const characterPosition = this.character.object.position.clone()
    const desiredCamera = characterPosition.clone().add(new Vector3(0, 2.15, 6.7))
    this.camera.position.lerp(desiredCamera, Math.min(1, delta * 2.8))
    this.camera.lookAt(characterPosition.clone().add(new Vector3(0, 1.35, 0)))
    this.character.update(
      this.navigationPath.length > 0
        ? { ...this.state, activity: 'walk' }
        : this.state,
      delta,
    )
    this.renderer.render(this.scene, this.camera)
  }

  private advanceNavigation(deltaSeconds: number) {
    const target = this.navigationPath[0]
    if (!target) return
    const position = this.character.object.position
    const remaining = position.distanceTo(target)
    const step = 2.2 * deltaSeconds
    if (remaining <= step) {
      position.copy(target)
      this.navigationPath.shift()
      return
    }
    position.addScaledVector(target.clone().sub(position).normalize(), step)
  }

  private resize() {
    const width = Math.max(1, this.host.clientWidth)
    const height = Math.max(1, this.host.clientHeight)
    this.renderer.setSize(width, height, false)
    this.camera.aspect = width / height
    this.camera.updateProjectionMatrix()
  }
}
