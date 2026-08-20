import { createApp } from 'vue'
import './style.css'

const surface = new URLSearchParams(window.location.search).get('gahyeonSurface')
const component = surface === 'controls'
  ? (await import('./CharacterControlPanel.vue')).default
  : (await import('./App.vue')).default

createApp(component).mount('#app')
