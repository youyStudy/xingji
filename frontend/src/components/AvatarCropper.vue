<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Loader2, X } from 'lucide-vue-next'
import { uploadMedia, mediaUrl } from '../api/xingji'

/**
 * 头像取景层：整张原图铺底（始终完整可见），正中一个圆形取景框。
 * 拖动图片换取景，圆框必须始终落在图片内；确定后把圆内那块裁成圆形头像上传。
 * 只负责取景与上传，拿到新地址后怎么落地由父级决定（弹窗填进表单，主页直接保存）。
 */
const props = defineProps({
  src: { type: String, required: true },
  alt: { type: String, default: '头像' }
})
const emit = defineEmits(['applied', 'cancel'])

const ZMAX = 3 // 最大放大倍数
const layer = ref(null)
const imgEl = ref(null)
const frame = ref(null)
const pos = ref({ x: 0, y: 0 })
const zoom = ref(1)
const box = ref({ left: 0, top: 0, w: 0, h: 0 }) // 图片在视口里的实际矩形
const stage = ref({ w: 0, h: 0 }) // 图片显示尺寸；圆框固定居中，四周压暗
const busy = ref(false)
const err = ref('')

const imgStyle = computed(() => ({
  left: `${box.value.left}px`,
  top: `${box.value.top}px`,
  width: `${box.value.w}px`,
  height: `${box.value.h}px`,
  transform: `translate(${pos.value.x}px, ${pos.value.y}px)`
}))
const frameStyle = computed(() => {
  const d = frameSize.value
  return {
    left: `calc(50% - ${d / 2}px)`,
    top: `calc(50% - ${d / 2}px)`,
    width: `${d}px`,
    height: `${d}px`
  }
})

const frameSize = ref(0)
let nat = { w: 0, h: 0 }
let scale = 1
let lim = { x: 0, y: 0 }

function layout() {
  if (!nat.w) return
  const vw = window.innerWidth
  const vh = window.innerHeight
  // 基础适配比例（zoom=1 时整张图刚好看得见）
  scale = Math.min((vw * 0.88) / nat.w, (vh * 0.74) / nat.h)
  // 取景框：照片显示短边的 86%，再用视口短边 72% 兜底
  // 圆已经接近短边，剩下的取景空间交给缩放滑块
  frameSize.value = Math.min(Math.min(nat.w, nat.h) * scale * 0.86, Math.min(vw, vh) * 0.72)
  applyZoom()
}
// 缩放只改图片显示尺寸与可拖范围，圆框始终居中不动
function applyZoom() {
  const eff = scale * zoom.value
  const st = { w: nat.w * eff, h: nat.h * eff }
  stage.value = st
  box.value = { left: (window.innerWidth - st.w) / 2, top: (window.innerHeight - st.h) / 2, w: st.w, h: st.h }
  const f = frameSize.value
  // 拖动上限：圆框不许跑出图片
  lim = {
    x: Math.max(0, (st.w - f) / 2),
    y: Math.max(0, (st.h - f) / 2)
  }
  clamp()
}
function clamp() {
  pos.value = {
    x: Math.min(lim.x, Math.max(-lim.x, pos.value.x)),
    y: Math.min(lim.y, Math.max(-lim.y, pos.value.y))
  }
}

function onDown(e) {
  if (busy.value) return
  e.preventDefault()
  const sx = e.clientX
  const sy = e.clientY
  const ox = pos.value.x
  const oy = pos.value.y
  const move = (ev) => {
    pos.value = { x: ox + (ev.clientX - sx), y: oy + (ev.clientY - sy) }
    clamp()
  }
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

async function apply() {
  if (busy.value) return
  const img = imgEl.value
  if (!img || !nat.w) return
  busy.value = true
  err.value = ''
  try {
    const f = frameSize.value
    const eff = scale * zoom.value
    // 圆框在视口正中；把它换算回图片的自然像素坐标
    const cx = window.innerWidth / 2
    const cy = window.innerHeight / 2
    const sx = (cx - f / 2 - box.value.left - pos.value.x) / eff
    const sy = (cy - f / 2 - box.value.top - pos.value.y) / eff
    const ss = f / eff
    // 导出尺寸跟着取景区的原始像素走（256~1024）：不放大成糊图，也不越裁越小
    const out = Math.min(1024, Math.max(256, Math.round(ss)))
    const cv = document.createElement('canvas')
    cv.width = cv.height = out
    const ctx = cv.getContext('2d')
    // 导出正方形而非圆形：所有头像位都用 border-radius 裁成圆，正方形既能反复重取，也不会留透明角
    ctx.drawImage(img, sx, sy, ss, ss, 0, 0, out, out)
    const blob = await new Promise((res) => cv.toBlob(res, 'image/png'))
    if (!blob) throw new Error('图片读取失败，换一张试试')
    const file = new File([blob], 'avatar.png', { type: 'image/png' })
    const media = await uploadMedia(file, 'avatar')
    emit('applied', mediaUrl(media.filePath))
  } catch (e) {
    // 外链图片会被浏览器判为跨域画布，裁不了，说人话
    err.value = /security|cross-origin|tainted/i.test(String(e?.message))
      ? '外部链接的图片不能直接取景，请先上传本地图片'
      : e?.message || '取景失败，请重试'
    busy.value = false
  }
}

watch(zoom, applyZoom)

onMounted(() => {
  const img = imgEl.value
  const boot = () => {
    nat = { w: img.naturalWidth, h: img.naturalHeight }
    layout()
  }
  if (img.complete && img.naturalWidth) boot()
  else img.addEventListener('load', boot)
  img.addEventListener('error', () => (err.value = '头像图片加载失败'))
  window.addEventListener('resize', layout)
})
</script>

<template>
  <div class="crop" ref="layer" @pointerdown="onDown">
    <button class="cp-x" type="button" aria-label="关闭" @click.stop="emit('cancel')"><X :size="20" /></button>

    <img ref="imgEl" class="cp-img" :src="src" :alt="alt" draggable="false" :style="imgStyle" />

    <!-- 压暗圆框以外的部分：用超粗环带遮住整屏，中间留出取景窗 -->
    <div class="cp-dim" aria-hidden="true" :style="frameStyle"></div>
    <div class="cp-frame" aria-hidden="true" :style="frameStyle"></div>

    <div class="cp-bar" @pointerdown.stop>
      <p class="cp-tip">
        <span v-if="err" class="cp-err">{{ err }}</span>
        <span v-else>拖动照片，把想露出的部分放进圆框里</span>
      </p>
      <div class="cp-zoom">
        <label class="cp-zlab" for="av-zoom">缩放</label>
        <input id="av-zoom" type="range" min="1" :max="ZMAX" step="0.02" v-model.number="zoom" />
        <span class="cp-zval">{{ Math.round(zoom * 100) }}%</span>
      </div>
      <div class="cp-acts">
        <button class="btn btn-ghost" type="button" :disabled="busy" @click="emit('cancel')">取消</button>
        <button class="btn solid" type="button" :disabled="busy" @click="apply">
          <Loader2 v-if="busy" :size="15" class="spin" />
          <span>{{ busy ? '处理中…' : '用这个当头像' }}</span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.crop { position: fixed; inset: 0; z-index: 140; overflow: hidden; cursor: grab; background: #0b1420; touch-action: none; }
.crop:active { cursor: grabbing; }
.cp-img { position: absolute; max-width: none; user-select: none; -webkit-user-drag: none; }
.cp-dim {
  position: absolute; border-radius: 50%; pointer-events: none;
  box-shadow: 0 0 0 9999px rgba(8, 16, 28, 0.62);
}
.cp-frame {
  position: absolute; border-radius: 50%; pointer-events: none;
  border: 1px solid rgb(255 255 255 / 0.85);
  box-shadow: 0 0 0 1px rgb(255 255 255 / 0.12), 0 18px 44px rgba(0, 0, 0, 0.45);
}
.cp-x {
  position: absolute; top: 18px; right: 22px; z-index: 2;
  width: 40px; height: 40px; border-radius: 50%; border: 0;
  background: rgba(255, 255, 255, 0.14); color: #fff;
  display: grid; place-items: center; cursor: pointer;
}
.cp-x:hover { background: rgba(255, 255, 255, 0.26); }
.cp-bar {
  position: absolute; left: 0; right: 0; bottom: 0; z-index: 2; cursor: default;
  display: flex; align-items: center; justify-content: space-between; gap: 18px; flex-wrap: wrap;
  padding: 18px 26px; background: rgba(8, 16, 28, 0.72); backdrop-filter: blur(8px);
}
.cp-tip { margin: 0; font-size: 13px; color: rgb(255 255 255 / 0.78); }
.cp-err { color: #ffb4a8; }
.cp-zoom { display: flex; align-items: center; gap: 10px; }
.cp-zlab { font-size: 12px; color: rgb(255 255 255 / 0.6); letter-spacing: 0.04em; }
.cp-zoom input[type="range"] { width: 168px; accent-color: #fff; cursor: pointer; }
.cp-zval { font-size: 12px; color: rgb(255 255 255 / 0.78); min-width: 42px; font-variant-numeric: tabular-nums; }
.cp-acts { display: flex; gap: 10px; }
.btn { border: 0; border-radius: 999px; padding: 9px 18px; font-size: 13.5px; cursor: pointer; font-family: var(--f-body); }
.btn-ghost { background: rgba(255, 255, 255, 0.12); color: #fff; }
.btn-ghost:hover { background: rgba(255, 255, 255, 0.2); }
.solid { background: #fff; color: #16202c; font-weight: 600; }
.solid:hover { transform: translateY(-1px); }
.btn:disabled { opacity: 0.6; cursor: progress; }
.spin { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
