import {
  AmbientLight,
  Color,
  DirectionalLight,
  Fog,
  GridHelper,
  Mesh,
  MeshStandardMaterial,
  PerspectiveCamera,
  PlaneGeometry,
  Scene,
  SRGBColorSpace,
  Vector3,
  WebGLRenderer,
} from 'three'
import type { CharacterRenderer } from './character-renderer'
import { PlaceholderCharacterRenderer } from './placeholder-character'
import type { StageState } from './stage-state'

export class ThreeStage {
  private readonly scene = new Scene()
  private readonly camera = new PerspectiveCamera(34, 1, 0.1, 100)
  private readonly renderer: WebGLRenderer
  private lastFrameTime = performance.now()
  private readonly observer: ResizeObserver
  private character: CharacterRenderer = new PlaceholderCharacterRenderer()
  private state: StageState
  private frame?: number

  constructor(private readonly host: HTMLElement, initialState: StageState) {
    this.state = initialState
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
    const floor = new Mesh(
      new PlaneGeometry(40, 40),
      new MeshStandardMaterial({ color: '#1b1d28', roughness: 0.94 }),
    )
    floor.rotation.x = -Math.PI / 2
    floor.receiveShadow = true
    const grid = new GridHelper(24, 24, '#5d596b', '#302f3c')
    grid.position.y = 0.002
    this.scene.add(ambient, key, floor, grid, this.character.object)
    this.camera.position.set(0, 2.15, 6.7)

    this.observer = new ResizeObserver(() => this.resize())
    this.observer.observe(host)
    this.resize()
    this.frame = requestAnimationFrame(this.render)
  }

  setState(state: StageState) {
    this.state = state
  }

  setCharacter(character: CharacterRenderer) {
    this.scene.remove(this.character.object)
    this.character.dispose()
    this.character = character
    this.scene.add(character.object)
  }

  dispose() {
    if (this.frame !== undefined) cancelAnimationFrame(this.frame)
    this.observer.disconnect()
    this.character.dispose()
    this.renderer.dispose()
    this.renderer.domElement.remove()
  }

  private readonly render = (frameTime: number) => {
    const delta = Math.min((frameTime - this.lastFrameTime) / 1_000, 0.05)
    this.lastFrameTime = frameTime
    const target = new Vector3(
      this.state.position.x,
      this.state.position.y,
      this.state.position.z,
    )
    this.character.object.position.x += (target.x - this.character.object.position.x) * Math.min(1, delta * 4)
    this.character.object.position.z += (target.z - this.character.object.position.z) * Math.min(1, delta * 4)
    const desiredCamera = target.clone().add(new Vector3(0, 2.15, 6.7))
    this.camera.position.lerp(desiredCamera, Math.min(1, delta * 2.8))
    this.camera.lookAt(target.clone().add(new Vector3(0, 1.35, 0)))
    this.character.update(this.state, delta)
    this.renderer.render(this.scene, this.camera)
    this.frame = requestAnimationFrame(this.render)
  }

  private resize() {
    const width = Math.max(1, this.host.clientWidth)
    const height = Math.max(1, this.host.clientHeight)
    this.renderer.setSize(width, height, false)
    this.camera.aspect = width / height
    this.camera.updateProjectionMatrix()
  }
}
