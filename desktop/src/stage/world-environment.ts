import {
  BoxGeometry,
  Group,
  Mesh,
  MeshStandardMaterial,
  PlaneGeometry,
} from 'three'

export class GahyeonHomeEnvironment {
  readonly object = new Group()
  private readonly geometries = new Set<BoxGeometry | PlaneGeometry>()
  private readonly materials = new Set<MeshStandardMaterial>()

  constructor() {
    this.roomFloor('bedroom', 0, -1, 6, 5, '#2b2935')
    this.roomFloor('living_room', 0, -7, 8, 6, '#262d35')
    this.roomFloor('workspace', 7, -2, 5, 5, '#302b34')
    this.roomFloor('hall-bedroom-workspace', 3.6, -2.5, 3.2, 1.7, '#292b34')
    this.roomFloor('hall-bedroom-living', 0, -3.7, 2, 1.5, '#292b34')

    this.furniture('bed', -1.8, 0.3, 0, 1.8, 0.55, 2.2, '#6f667a')
    this.furniture('bed-head', -1.8, 0.7, 0.9, 1.8, 0.9, 0.18, '#514a5c')
    this.furniture('desk', 7.8, 0.55, -2.4, 1.8, 0.12, 0.8, '#665447')
    this.furniture('bookshelf', 3.4, 1.2, -7.8, 0.45, 2.4, 2.2, '#564a43')
    this.furniture('chair', -2.2, 0.45, -5.4, 0.9, 0.9, 0.9, '#526270')
    this.furniture('window-frame', 0, 1.5, -9.8, 2.8, 2.3, 0.12, '#758596')

    this.wall(-3.1, 1.25, -1, 0.15, 2.5, 5.2)
    this.wall(3.1, 1.25, -0.7, 0.15, 2.5, 4.4)
    this.wall(-4.1, 1.25, -7, 0.15, 2.5, 6.2)
    this.wall(4.1, 1.25, -7.7, 0.15, 2.5, 4.5)
    this.wall(7, 1.25, -4.6, 5.2, 2.5, 0.15)
  }

  dispose() {
    this.geometries.forEach(geometry => geometry.dispose())
    this.materials.forEach(material => material.dispose())
  }

  private roomFloor(name: string, x: number, z: number, width: number, depth: number, color: string) {
    const geometry = new PlaneGeometry(width, depth)
    const material = new MeshStandardMaterial({ color, roughness: 0.92 })
    const floor = new Mesh(geometry, material)
    floor.name = name
    floor.rotation.x = -Math.PI / 2
    floor.position.set(x, 0, z)
    floor.receiveShadow = true
    this.geometries.add(geometry)
    this.materials.add(material)
    this.object.add(floor)
  }

  private furniture(
    name: string,
    x: number,
    y: number,
    z: number,
    width: number,
    height: number,
    depth: number,
    color: string,
  ) {
    const geometry = new BoxGeometry(width, height, depth)
    const material = new MeshStandardMaterial({ color, roughness: 0.78 })
    const mesh = new Mesh(geometry, material)
    mesh.name = name
    mesh.position.set(x, y, z)
    mesh.castShadow = true
    mesh.receiveShadow = true
    this.geometries.add(geometry)
    this.materials.add(material)
    this.object.add(mesh)
  }

  private wall(x: number, y: number, z: number, width: number, height: number, depth: number) {
    this.furniture('wall', x, y, z, width, height, depth, '#343541')
  }
}
