<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'

const MODEL_URL = '/app-assets/659cfa2a90d7616c58ad50eab1a7e5b0.glb'
const FALLBACK_IMAGE = '/app-assets/happy-companion.png'
const MODEL_HEIGHT = 2.42
const MODEL_OFFSET_X = 0.34
const MODEL_OFFSET_Y = -0.18
const shaderTimeUniform = { value: 0 }

const host = ref<HTMLDivElement>()
const loading = ref(true)
const failed = ref(false)

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let resizeObserver: ResizeObserver | null = null
let frameId = 0
let model: THREE.Object3D | null = null
let disposed = false

function getMaterials(material: THREE.Material | THREE.Material[]) {
  return Array.isArray(material) ? material : [material]
}

function tuneMaterial(material: THREE.Material) {
  const pbr = material as THREE.MeshStandardMaterial
  if (pbr.map) {
    pbr.map.colorSpace = THREE.SRGBColorSpace
  }
  if ('metalness' in pbr) {
    pbr.metalness = Math.min(pbr.metalness ?? 0.2, 0.48)
  }
  if ('roughness' in pbr) {
    pbr.roughness = Math.max(pbr.roughness ?? 0.34, 0.28)
  }
  material.onBeforeCompile = shader => {
    shader.uniforms.uPlanetShaderTime = shaderTimeUniform
    shader.uniforms.uPlanetRimColor = { value: new THREE.Color(0x62f2ff) }
    shader.uniforms.uPlanetWarmColor = { value: new THREE.Color(0xffd166) }
    shader.fragmentShader = shader.fragmentShader.replace(
      '#include <common>',
      `
        #include <common>
        uniform float uPlanetShaderTime;
        uniform vec3 uPlanetRimColor;
        uniform vec3 uPlanetWarmColor;
      `,
    )
    shader.fragmentShader = shader.fragmentShader.replace(
      '#include <dithering_fragment>',
      `
        float planetRim = pow(1.0 - clamp(abs(dot(normalize(normal), normalize(vViewPosition))), 0.0, 1.0), 1.7);
        float planetScan = 0.5 + 0.5 * sin(gl_FragCoord.y * 0.075 + uPlanetShaderTime * 2.4);
        float planetPulse = 0.55 + 0.45 * sin(uPlanetShaderTime * 1.35);
        vec3 planetGlow = uPlanetRimColor * planetRim * (0.42 + planetScan * 0.24);
        vec3 planetWarm = uPlanetWarmColor * planetRim * planetPulse * 0.16;
        gl_FragColor.rgb = mix(gl_FragColor.rgb, gl_FragColor.rgb * vec3(0.84, 1.12, 1.18), 0.22);
        gl_FragColor.rgb += planetGlow + planetWarm;
        #include <dithering_fragment>
      `,
    )
  }
  material.customProgramCacheKey = () => 'planet-hero-energy-shader-v1'
  material.needsUpdate = true
}

function prepareModel(object: THREE.Object3D) {
  object.traverse(child => {
    const mesh = child as THREE.Mesh
    if (!mesh.isMesh) return
    mesh.castShadow = true
    mesh.receiveShadow = false
    mesh.frustumCulled = false
    getMaterials(mesh.material).forEach(tuneMaterial)
  })

  const box = new THREE.Box3().setFromObject(object)
  const size = new THREE.Vector3()
  const center = new THREE.Vector3()
  box.getSize(size)
  box.getCenter(center)

  object.position.sub(center)
  const height = size.y || Math.max(size.x, size.z) || 1
  object.scale.setScalar(MODEL_HEIGHT / height)
  object.position.x += MODEL_OFFSET_X
  object.position.y += MODEL_OFFSET_Y
  object.userData.baseX = object.position.x
  object.userData.baseY = object.position.y
  object.rotation.set(0.08, -0.36, 0)
}

function resize() {
  if (!host.value || !renderer || !camera) return
  const width = Math.max(host.value.clientWidth, 1)
  const height = Math.max(host.value.clientHeight, 1)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
  renderer.setSize(width, height, false)
  camera.aspect = width / height
  camera.updateProjectionMatrix()
}

function disposeObject(object: THREE.Object3D) {
  object.traverse(child => {
    const mesh = child as THREE.Mesh
    if (!mesh.isMesh) return
    mesh.geometry?.dispose()
    getMaterials(mesh.material).forEach(material => {
      const pbr = material as THREE.MeshStandardMaterial
      pbr.map?.dispose()
      pbr.normalMap?.dispose()
      pbr.metalnessMap?.dispose()
      pbr.roughnessMap?.dispose()
      material.dispose()
    })
  })
}

function animate() {
  if (disposed || !renderer || !scene || !camera) return
  const time = performance.now() * 0.001
  if (model) {
    shaderTimeUniform.value = time
    model.rotation.y = -0.36 + Math.sin(time * 0.7) * 0.14
    model.position.x = model.userData.baseX
    model.position.y = model.userData.baseY + Math.sin(time * 1.3) * 0.035
  }
  renderer.render(scene, camera)
  frameId = window.requestAnimationFrame(animate)
}

onMounted(() => {
  if (!host.value) return

  scene = new THREE.Scene()
  camera = new THREE.PerspectiveCamera(28, 1, 0.1, 100)
  camera.position.set(0, 0.12, 5.8)

  renderer = new THREE.WebGLRenderer({
    alpha: true,
    antialias: true,
    powerPreference: 'high-performance',
  })
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.12
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFShadowMap
  host.value.appendChild(renderer.domElement)

  const ambient = new THREE.HemisphereLight(0xf7fbff, 0x182235, 2.4)
  const key = new THREE.DirectionalLight(0xffffff, 3.1)
  key.position.set(2.6, 3.4, 4.2)
  key.castShadow = true
  const rim = new THREE.DirectionalLight(0x6bf4ff, 1.4)
  rim.position.set(-3.2, 1.8, -2.4)
  const glow = new THREE.PointLight(0xffd166, 1.2, 5.4)
  glow.position.set(0.9, 1.2, 2.2)
  scene.add(ambient, key, rim, glow)

  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(host.value)
  resize()

  const loader = new GLTFLoader()
  loader.load(
    MODEL_URL,
    gltf => {
      if (disposed || !scene) return
      const object = gltf.scene
      prepareModel(object)
      model = object
      scene.add(object)
      loading.value = false
    },
    undefined,
    () => {
      loading.value = false
      failed.value = true
    },
  )

  animate()
})

onBeforeUnmount(() => {
  disposed = true
  window.cancelAnimationFrame(frameId)
  resizeObserver?.disconnect()
  if (model) {
    disposeObject(model)
  }
  if (renderer) {
    renderer.dispose()
    renderer.forceContextLoss()
    renderer.domElement.remove()
  }
})
</script>

<template>
  <div ref="host" class="planet-hero-model" :class="{ 'is-loading': loading, 'has-failed': failed }">
    <span v-if="loading" class="model-loader"></span>
    <img v-if="failed" :src="FALLBACK_IMAGE" alt="" />
  </div>
</template>

<style scoped lang="scss">
.planet-hero-model {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: visible;

  :deep(canvas) {
    position: absolute;
    inset: 0;
    display: block;
    width: 100% !important;
    height: 100% !important;
    filter: drop-shadow(0 24px 32px rgba(0, 0, 0, 0.38));
  }

  img {
    position: absolute;
    right: 0;
    bottom: 0;
    width: 232px;
    height: 290px;
    object-fit: cover;
    object-position: center;
    border-radius: 40px;
    filter: drop-shadow(0 24px 40px rgba(0, 0, 0, 0.4));
  }
}

.model-loader {
  position: absolute;
  right: 58px;
  bottom: 68px;
  width: 92px;
  height: 92px;
  border-radius: 50%;
  background:
    radial-gradient(circle at 50% 48%, rgba(255, 209, 102, 0.82), transparent 34%),
    radial-gradient(circle, rgba(73, 215, 209, 0.2), rgba(73, 215, 209, 0));
  animation: model-pulse 1.2s ease-in-out infinite;
}

@keyframes model-pulse {
  0%,
  100% {
    opacity: 0.46;
    transform: scale(0.92);
  }

  50% {
    opacity: 0.82;
    transform: scale(1.08);
  }
}
</style>
