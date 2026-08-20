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
import type { PendingWorldAction, StageState, Vector3State } from './stage-state'
import { GahyeonHomeEnvironment, type WorldEnvironment } from './world-environment'
import { WorldActionInteractionGate } from './world-action-interaction'

export class ThreeStage {
  private readonly scene = new Scene()
  private readonly camera = new PerspectiveCamera(34, 1, 0.1, 100)
  private readonly renderer: WebGLRenderer
  private lastFrameTime = performance.now()
  private readonly observer: ResizeObserver
  private environment: WorldEnvironment = new GahyeonHomeEnvironment()
  private character: CharacterRenderer = new PlaceholderCharacterRenderer()
  private state: StageState
  private currentRoom: string
  private navigationTargetRoom: string
  private navigationPath: Vector3[] = []
  private notifiedWorldActionId?: string
  private readonly worldActionInteraction = new WorldActionInteractionGate()
  private lookingGlassInitialized = false
  private cameraPreset: CameraPreset = 'full-body'

  constructor(
    private readonly host: HTMLElement,
    initialState: StageState,
    private readonly onWorldActionArrived?: (action: PendingWorldAction) => void,
    options: ThreeStageOptions = {},
  ) {
    this.state = initialState
    this.currentRoom = initialState.room
    this.navigationTargetRoom = initialState.room
    this.scene.background = options.transparent ? null : new Color('#171924')
    this.scene.fog = options.transparent ? null : new Fog('#171924', 9, 24)
    this.renderer = new WebGLRenderer({ antialias: true, alpha: options.transparent === true })
    if (options.transparent) this.renderer.setClearColor(0x000000, 0)
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
    this.scene.add(ambient, key, this.character.object)
    if (!options.transparent) this.scene.add(grid, this.environment.object)
    this.character.object.position.set(
      initialState.position.x,
      initialState.position.y,
      initialState.position.z,
    )
    this.navigationPath = initialNavigationPath(initialState)
      .map(point => new Vector3(point.x, point.y, point.z))
    this.navigationTargetRoom = stageDestination(initialState).room
    this.camera.position.set(0, 2.15, 6.7)

    this.observer = new ResizeObserver(() => this.resize())
    this.observer.observe(host)
    this.resize()
    this.renderer.setAnimationLoop(this.render)
  }

  setState(state: StageState) {
    const previousDestination = stageDestination(this.state)
    const destination = stageDestination(state)
    const actionChanged = state.pendingWorldAction?.actionId
      !== this.state.pendingWorldAction?.actionId
    const destinationChanged = !samePosition(destination.position, previousDestination.position)
      || destination.room !== previousDestination.room
    if (destinationChanged) {
      this.navigationPath = buildNavigationPath(
        this.currentRoom,
        destination.room,
        destination.position,
      )
        .map(point => new Vector3(point.x, point.y, point.z))
      this.navigationTargetRoom = destination.room
    }
    if (destinationChanged || actionChanged) this.notifiedWorldActionId = undefined
    this.state = state
  }

  setCharacter(character: CharacterRenderer) {
    const position = this.character.object.position.clone()
    this.scene.remove(this.character.object)
    this.character.dispose()
    this.character = character
    this.character.object.position.copy(position)
    this.scene.add(character.object)
  }

  setEnvironment(environment: WorldEnvironment) {
    this.scene.remove(this.environment.object)
    this.environment.dispose()
    this.environment = environment
    this.scene.add(environment.object)
  }

  setCameraPreset(preset: CameraPreset) {
    this.cameraPreset = preset
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
    this.environment.dispose()
    this.renderer.dispose()
    this.renderer.domElement.remove()
  }

  private readonly render = (frameTime: number) => {
    const delta = Math.min((frameTime - this.lastFrameTime) / 1_000, 0.05)
    this.lastFrameTime = frameTime
    const reflexOwnsPresentation = isImmediateActivity(this.state.activity)
    if (!reflexOwnsPresentation) this.advanceNavigation(delta)
    const characterPosition = this.character.object.position.clone()
    const framing = cameraFraming(this.cameraPreset)
    const desiredCamera = characterPosition.clone().add(framing.offset)
    this.camera.position.lerp(desiredCamera, Math.min(1, delta * 2.8))
    this.camera.lookAt(characterPosition.clone().add(framing.target))
    this.character.update(
      presentationState(this.state, this.navigationPath.length > 0, reflexOwnsPresentation),
      delta,
    )
    this.renderer.render(this.scene, this.camera)
    this.notifyWorldActionArrival(delta, reflexOwnsPresentation)
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
      if (this.navigationPath.length === 0) {
        this.currentRoom = this.navigationTargetRoom
      }
      return
    }
    position.addScaledVector(target.clone().sub(position).normalize(), step)
  }

  private notifyWorldActionArrival(deltaSeconds: number, paused: boolean) {
    const action = this.state.pendingWorldAction
    if (action?.actionId === this.notifiedWorldActionId) return
    const destination = stageDestination(this.state)
    const arrived = destination.room === this.currentRoom
      && samePosition(destination.position, vectorState(this.character.object.position), 0.025)
    if (!this.worldActionInteraction.advance(action, arrived, deltaSeconds, paused) || !action) return
    this.notifiedWorldActionId = action.actionId
    this.onWorldActionArrived?.(action)
  }

  private resize() {
    const width = Math.max(1, this.host.clientWidth)
    const height = Math.max(1, this.host.clientHeight)
    this.renderer.setSize(width, height, false)
    this.camera.aspect = width / height
    this.camera.updateProjectionMatrix()
  }
}

export type CameraPreset = 'face' | 'bust' | 'full-body'

export type ThreeStageOptions = { transparent?: boolean }

export function cameraFraming(preset: CameraPreset) {
  if (preset === 'face') {
    return { offset: new Vector3(0, 1.68, 1.55), target: new Vector3(0, 1.62, 0) }
  }
  if (preset === 'bust') {
    return { offset: new Vector3(0, 1.72, 3.15), target: new Vector3(0, 1.42, 0) }
  }
  return { offset: new Vector3(0, 2.15, 6.7), target: new Vector3(0, 1.35, 0) }
}

export function stageDestination(state: StageState) {
  return state.pendingWorldAction
    ? { room: state.pendingWorldAction.room, position: state.pendingWorldAction.position }
    : { room: state.room, position: state.position }
}

/** Rehydrates a Core target that may have arrived before the async stage mounted. */
export function initialNavigationPath(state: StageState) {
  const action = state.pendingWorldAction
  return action
    ? buildNavigationPath(state.room, action.room, action.position)
    : []
}

export function presentationState(
  state: StageState,
  navigating: boolean,
  reflexOwnsPresentation = isImmediateActivity(state.activity),
): StageState {
  if (reflexOwnsPresentation) return state
  if (navigating) return { ...state, activity: 'walk' }
  return state.pendingWorldAction
    ? { ...state, activity: state.pendingWorldAction.activity }
    : state
}

function isImmediateActivity(activity: string) {
  return activity === 'attention'
    || activity === 'listening'
    || activity === 'thinking'
    || activity === 'conversation'
}

function vectorState(position: Vector3): Vector3State {
  return { x: position.x, y: position.y, z: position.z }
}

function samePosition(left: Vector3State, right: Vector3State, epsilon = 0) {
  return Math.abs(left.x - right.x) <= epsilon
    && Math.abs(left.y - right.y) <= epsilon
    && Math.abs(left.z - right.z) <= epsilon
}
