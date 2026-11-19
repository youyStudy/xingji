<script setup>
import { ref, shallowRef, watch, onBeforeUnmount, computed, nextTick } from 'vue'
import { Loader2, MapPin, Layers, Minus, Plus, ChevronLeft, ChevronRight, Crosshair, Search } from 'lucide-vue-next'

const props = defineProps({
  itinerary: { type: Object, default: null },
  activeDay: { type: Number, default: 0 },
  city: { type: String, default: '' }
})
const emit = defineEmits(['set-day', 'pick-city', 'search-open'])

// 高德 JS API 配置只从本地环境变量读取；公开仓库不内置任何真实 Key。
const JS_KEY = import.meta.env.VITE_AMAP_JS_KEY || ''
const SEC_CODE = import.meta.env.VITE_AMAP_SECURITY_CODE || ''

const mountEl = ref(null)
const map = shallowRef(null)
const AMapRef = shallowRef(null)
const status = ref(JS_KEY ? 'loading' : 'nokey') // loading | ready | nokey | error
const markers = shallowRef([])
const polylines = shallowRef([])
const info = ref(null) // { name, address, type, time }
const zoomPct = ref(100)

// 底图模式:satellite(卫星影像+路网地名) | standard(标准矢量)
// 默认标准:全国概览那一级卫星瓦片一个像素铺近十公里,重采样后必然发糊;矢量图任何 zoom 都锐利。
// 想看真实影像用右上角切换,放大到城市级卫星就不糊了。
const baseMode = ref('standard')
let satLayer = null    // AMap.TileLayer.Satellite
let roadLayer = null   // AMap.TileLayer.RoadNet(叠在卫星上提供道路/地名)
let satOn = false      // 卫星图层当前是否已叠加(矢量底图常驻,切标准=移除卫星叠层)

/* ---------- 探索态状态 ---------- */
let programmaticMoveUntil = 0 // 避免把程序主动居中误判成用户拖动

// 搜索浮层(位于地图之上):城市→锁定辖区高亮;景点→锁定定位
const searchQ = ref('')
const sugList = ref([])       // 高德 AutoComplete 联想建议
const ddOpen = ref(false)     // 联想下拉是否展开
const searching = ref(false)  // 搜索进行中
const searchHint = ref('')    // 未命中等提示
let autoComplete = null       // AMap.AutoComplete 实例(懒建)
let sugSeq = 0                // 联想请求序号:提交搜索后作废在途回调,避免下拉重新盖住已定位的地图
let spotPin = null            // 景点搜索结果地图钉

const dayList = computed(() => (props.itinerary?.days || []).map((d, i) => ({ i, day: d.day, nodes: d.nodes || [] })))
const activeNodes = computed(() => props.itinerary?.days?.[props.activeDay]?.nodes || [])

let loadToken = 0
function loadScript() {
  if (window.AMap) return Promise.resolve(window.AMap)
  if (!JS_KEY) { status.value = 'nokey'; return Promise.reject(new Error('no key')) }
  if (window.__amapLoading) return window.__amapLoading
  if (SEC_CODE) window._AMapSecurityConfig = { securityJsCode: SEC_CODE }
  const p = new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = `https://webapi.amap.com/maps?v=2.0&key=${JS_KEY}&plugin=AMap.Geocoder,AMap.ToolBar,AMap.Scale,AMap.DistrictSearch,AMap.AutoComplete,AMap.PlaceSearch`
    s.async = true
    s.onload = () => resolve(window.AMap)
    s.onerror = () => reject(new Error('AMap script load failed'))
    document.head.appendChild(s)
  })
  window.__amapLoading = p
  return p
}

async function initMap() {
  if (!JS_KEY) { status.value = 'nokey'; return }
  const my = ++loadToken
  status.value = 'loading'
  try {
    const AMap = await loadScript()
    if (my !== loadToken || !mountEl.value) return
    AMapRef.value = AMap
    // 标准档用高德 JSAPI 2.0 的默认矢量底图:按设备像素实时绘制,DPR>1 也不糊;
    // 栅格 TileLayer(webrd 256px 瓦片)在 150% 缩放下会被拉伸,所以只在卫星档叠加。
    map.value = new AMap.Map(mountEl.value, {
      viewMode: '2D',
      zoom: 4,
      center: [112, 30],
      resizeEnable: true
    })
    // 卫星影像 + 路网地名,按需叠加在矢量底图之上
    try { satLayer = new AMap.TileLayer.Satellite() } catch (e) { satLayer = null }
    try { roadLayer = new AMap.TileLayer.RoadNet({ opacity: 1 }) } catch (e) { roadLayer = null }
    satOn = false
    // 按当前模式挂载(默认标准=矢量)
    setBaseLayer(baseMode.value)
    map.value.addControl(new AMap.Scale())
    map.value.on('zoomend', () => {
      const z = map.value.getZoom()
      zoomPct.value = Math.round((z / 20) * 100)
    })
    // 点地图空白:仅收起景点详情卡(城市热点由浮层关闭按钮退出)
    map.value.on('click', () => { closeSpot() })
    // 引导线终点跟随城市中心:平移/缩放后重算扇形 anchor
    status.value = 'ready'
    // 关键:必须等地图 complete 之后再渲染/改视野。
    // 在初始化完成前调用 setBounds/setZoomAndCenter 会让地图进入冻结态、之后所有视角指令失效。
    let done = false
    const go = () => { if (done || my !== loadToken) return; done = true; renderDay() }
    map.value.on('complete', () => { if (map.value) map.value.__complete = true; go() })
    // 兜底:complete 万一不触发(缓存秒开),1.2s 后强制渲染一次
    setTimeout(go, 1200)
  } catch (e) {
    if (my === loadToken) status.value = 'error'
  }
}

const geocoder = shallowRef(null)
function ensureGeocoder() {
  const AMap = AMapRef.value
  if (!AMap) return null
  if (!geocoder.value) geocoder.value = new AMap.Geocoder({ city: props.city || '全国' })
  return geocoder.value
}

// 缓存 placeName → [lng,lat]，避免重复地理编码
const geoCache = new Map()
function geocode(name, city) {
  const key = `${city}|${name}`
  if (geoCache.has(key)) return Promise.resolve(geoCache.get(key))
  const g = ensureGeocoder()
  if (!g) return Promise.resolve(null)
  return new Promise((resolve) => {
    g.getLocation(name, (err, result) => {
      let ll = null
      if (!err && result && result.geocodes && result.geocodes.length) {
        const p = result.geocodes[0].location
        ll = [p.getLng(), p.getLat()]
        geoCache.set(key, ll)
      }
      resolve(ll)
    })
  })
}

let renderSeq = 0
async function renderDay() {
  const AMap = AMapRef.value
  const m = map.value
  if (!AMap || !m) return
  const seq = ++renderSeq
  const nodes = activeNodes.value
  clearOverlays()
  if (!nodes.length) {
    // 探索态(无行程):不再铺城市胶囊,改由顶部搜索框驱动城市/景点定位
    return
  }
  const pts = []
  for (const n of nodes) {
    // 优先用后端核验回填的真实坐标(n.lat/n.lng,已是 GCJ-02,高德地图直接用)。
    // 只有在拿不到坐标时才退回按地名地理编码——那是"猜"出来的位置,
    // 重名地点会画错,所以只能当兜底，不能当主路径。
    const ll = (n.lat != null && n.lng != null)
      ? [Number(n.lng), Number(n.lat)]
      : await geocode(n.placeName, props.city)
    if (seq !== renderSeq) return // 有更新的渲染，丢弃这次
    if (!ll) continue
    if (n.lat == null || n.lng == null) {
      // 没坐标的节点在地图上不该出现假点：跳过，让"未定位"只在列表里体现
      continue
    }
    pts.push(ll)
    const idx = pts.length
    const marker = new AMap.Marker({
      position: ll,
      anchor: 'bottom-center',
      content: `<div class="xj-pin">${idx}</div>`,
      offset: new AMap.Pixel(0, 0)
    })
    marker.on('click', () => {
      info.value = { name: n.placeName, type: n.nodeType, time: fmtTime(n), address: props.city }
    })
    m.add(marker)
    markers.value.push(marker)
  }
  if (pts.length >= 2) {
    const line = new AMap.Polyline({
      path: pts,
      isOutline: true,
      outlineColor: '#ffffff',
      strokeColor: '#0f3d63',
      strokeWeight: 4,
      strokeOpacity: 0.9,
      showDir: true,
      lineJoin: 'round',
      lineCap: 'round'
    })
    m.add(line)
    polylines.value.push(line)
  }
  if (pts.length) {
    m.setFitView(null, false, [80, 80, 80, 80], 15)
  }
}

/* ---------- 探索态:搜索驱动(行政区城市 / 景点 POI) ---------- */
function shortName(n) { return (n || '').replace(/(市|省|自治区|特别行政区|地区|盟|自治州)$/, '') }
// 判定 DistrictSearch 命中的行政区名是否与关键词同指一座城市(避免把 POI 误判成城市)
function isSameCity(dName, kw) { const s = shortName(dName); return dName === kw || s === kw || s === shortName(kw) }

function ensureAutoComplete() {
  const AMap = AMapRef.value
  if (!AMap) return null
  if (!autoComplete) {
    AMap.plugin('AMap.AutoComplete', () => { try { autoComplete = new AMap.AutoComplete({ city: '全国' }) } catch (e) { autoComplete = null } })
  }
  return autoComplete
}
function onSearchInput() {
  searchHint.value = ''
  const kw = searchQ.value.trim()
  if (!kw) { sugSeq++; sugList.value = []; ddOpen.value = false; return }
  const ac = ensureAutoComplete()
  if (!ac) { ddOpen.value = false; return }
  const seq = ++sugSeq
  // 2.0 SDK 的联想方法是 search(getTips 是 1.4 旧名,调用会抛 not a function)
  ac.search(kw, (status, result) => {
    if (seq !== sugSeq) return
    if (status === 'complete' && result && result.tips) {
      sugList.value = result.tips.filter((t) => t && t.name).slice(0, 8)
      ddOpen.value = sugList.value.length > 0
    } else { sugList.value = []; ddOpen.value = false }
  })
}
function closeDropdown() { setTimeout(() => { ddOpen.value = false }, 140) }
function chooseSug(t) {
  searchQ.value = t.name
  ddOpen.value = false
  const loc = Array.isArray(t.location) ? t.location : null
  runSearch(t.name, loc)
}
// 主入口:先判行政区(城市级)→ 锁定辖区+虚线高亮+5 卡;否则→ 锁定景点
function runSearch(kw, tipLoc) {
  kw = (kw || '').trim()
  if (!kw) return
  const AMap = AMapRef.value
  if (!AMap) return
  sugSeq++
  ddOpen.value = false
  searching.value = true
  searchHint.value = ''
  AMap.plugin('AMap.DistrictSearch', () => {
    let ds = null
    try { ds = new AMap.DistrictSearch({ level: 'city', subdistrict: 0, extensions: 'base' }) }
    catch (e) { searching.value = false; return }
    ds.search(kw, (status, result) => {
      const d = result && result.districtList && result.districtList[0]
      const hitCity = status === 'complete' && d && d.name && d.center && isSameCity(d.name, kw)
      if (hitCity) {
        searching.value = false
        const c = d.center
        const lng = c.getLng ? c.getLng() : c[0]
        const lat = c.getLat ? c.getLat() : c[1]
        closeSpot()
        pickCity({ name: shortName(d.name) || d.name, lng, lat })
      } else {
        searchSpot(kw, tipLoc)
      }
    })
  })
}
function searchSpot(kw, tipLoc) {
  const AMap = AMapRef.value
  if (!AMap) { searching.value = false; return }
  AMap.plugin('AMap.PlaceSearch', () => {
    let ps = null
    try { ps = new AMap.PlaceSearch({ city: '全国', citylimit: false, pageSize: 5, pageIndex: 1 }) }
    catch (e) { searching.value = false; return }
    ps.search(kw, (status, result) => {
      const pois = result && result.poiList && result.poiList.pois
      const poi = status === 'complete' && pois && pois.length ? pois[0] : null
      if (poi && poi.location) {
        searching.value = false
        focusSpot({
          name: poi.name,
          type: poi.type ? String(poi.type).split(';').pop() : '景点',
          address: (poi.pname || '') + (poi.cityname || '') + (poi.adname || '') + (poi.address ? ' ' + poi.address : ''),
          lng: poi.location.getLng(),
          lat: poi.location.getLat()
        })
      } else if (tipLoc) {
        searching.value = false
        focusSpot({ name: kw, type: '景点', address: '', lng: tipLoc[0], lat: tipLoc[1] })
      } else {
        geocode(kw, props.city || '').then((ll) => {
          searching.value = false
          if (ll) focusSpot({ name: kw, type: '景点', address: '', lng: ll[0], lat: ll[1] })
          else searchHint.value = '没找到相关景点或城市，换个词试试'
        })
      }
    })
  })
}
// 锁定单个景点:退出城市热点、清除高亮,加地图钉并复用顶部信息卡展示详情
function focusSpot(spot) {
  const m = map.value
  const AMap = AMapRef.value
  if (!m || !AMap) return
  if (spotPin) { m.remove(spotPin); spotPin = null }
  const mk = new AMap.Marker({
    position: [spot.lng, spot.lat],
    anchor: 'bottom-center',
    content: '<div class="xj-spot-pin"></div>',
    offset: new AMap.Pixel(0, 0),
    zIndex: 60
  })
  m.add(mk)
  spotPin = mk
  info.value = { name: spot.name, type: spot.type, address: spot.address || props.city || '', time: '' }
  programmaticMoveUntil = Date.now() + 650
  try { m.setZoomAndCenter(15, [spot.lng, spot.lat], false, 400) }
  catch (e) { try { m.setZoomAndCenter(15, [spot.lng, spot.lat]) } catch (_) {} }
}
function closeSpot() {
  info.value = null
  if (map.value && spotPin) { map.value.remove(spotPin); spotPin = null }
}

/* ---------- 选中城市：居中定位 ---------- */
function pickCity(c) {
  const m = map.value
  if (!m) return
  programmaticMoveUntil = Date.now() + 900
  emit('pick-city', c.name)
  try { m.setZoomAndCenter(9, [c.lng, c.lat], false, 500) }
  catch (e) { try { m.setZoomAndCenter(9, [c.lng, c.lat]) } catch (_) {} }
}

function clearOverlays() {
  const m = map.value
  if (!m) return
  if (markers.value.length) m.remove(markers.value)
  if (polylines.value.length) m.remove(polylines.value)
  markers.value = []
  polylines.value = []
  info.value = null
  if (spotPin) { m.remove(spotPin) }
  spotPin = null
}

function fmtTime(n) {
  const s = n.startTime || ''
  const e = n.endTime || ''
  const a = String(s).slice(0, 5)
  const b = String(e).slice(0, 5)
  if (a && b) return `${a} – ${b}`
  return a || b || ''
}

function zoom(dir) {
  const m = map.value
  if (!m) return
  dir > 0 ? m.zoomIn() : m.zoomOut()
}
// 切换底图:标准档=矢量底图常驻;卫星档=把影像+路网叠层加上去,切回时移除
function setBaseLayer(mode) {
  const m = map.value
  if (!m) return
  baseMode.value = mode
  const sat = [satLayer, roadLayer].filter(Boolean)
  if (!sat.length) return
  if (mode === 'satellite' && !satOn) {
    m.add(sat)
    satOn = true
  } else if (mode === 'standard' && satOn) {
    m.remove(sat)
    satOn = false
  }
}
function recenter() {
  const m = map.value
  if (!m) return
  if (markers.value.length) {
    m.setFitView(null, false, [80, 80, 80, 80], 15)
  } else {
    // 探索态:退出当前景点定位,回到全国概览
    closeSpot()
    m.setZoomAndCenter(4, [112, 30], false, 400)
  }
}

watch(() => props.activeDay, () => { if (status.value === 'ready') renderDay() })
watch(() => props.itinerary, async () => {
  geoCache.clear()
  if (status.value !== 'ready') { await initMap() } else { renderDay() }
})

// 联想下拉开合:通知父级淡出浮层,避免下拉被会话/行程浮层盖住
watch(ddOpen, (v) => emit('search-open', v))

onBeforeUnmount(() => {
  loadToken++
  clearOverlays()
  if (map.value) { map.value.destroy(); map.value = null }
})

// 首帧尝试初始化
nextTick(() => { if (JS_KEY) initMap() })
</script>

<template>
  <div class="map-panel">
    <!-- 真实地图挂载点:必须始终有尺寸(不能 v-show 隐藏),否则高德在 0 尺寸容器上初始化后投影失效,后续 setZoom/setCenter 全部无效。加载/兜底态以浮层盖在其上。 -->
    <div ref="mountEl" class="map-mount"></div>

    <!-- 搜索浮层:位于地图之上。搜城市→锁定辖区+虚线高亮+5 卡;搜景点→锁定定位+详情卡 -->
    <div v-if="status === 'ready'" class="map-search">
      <div class="ms-box">
        <Search :size="16" class="ms-ic" />
        <input
          class="ms-input"
          v-model="searchQ"
          placeholder="搜索城市或景点，如 重庆 / 洪崖洞"
          @input="onSearchInput"
          @keyup.enter="runSearch(searchQ)"
          @focus="ddOpen = sugList.length > 0"
          @blur="closeDropdown"
        />
        <button class="ms-go" :disabled="searching" @click="runSearch(searchQ)">{{ searching ? '搜索中…' : '搜索' }}</button>
      </div>
      <p v-if="searchHint" class="ms-hint">{{ searchHint }}</p>
      <ul v-if="ddOpen && sugList.length" class="ms-dd">
        <li v-for="(t, i) in sugList" :key="i" class="ms-item" @mousedown.prevent="chooseSug(t)">
          <span class="ms-name">{{ t.name }}</span>
          <span class="ms-region">{{ t.district || (typeof t.city === 'string' ? t.city : '') }}</span>
        </li>
      </ul>
    </div>

    <!-- 载入中 -->
    <div v-if="status === 'loading'" class="map-state">
      <Loader2 :size="22" class="spin" />
      <span>正在加载地图…</span>
    </div>

    <!-- 未配置 Key：结构化占位，仍把当天节点排出来，保证页面完整 -->
    <div v-else-if="status === 'nokey' || status === 'error'" class="map-fallback">
      <div class="fb-grid"></div>
      <div class="fb-inner">
        <div class="fb-badge"><MapPin :size="16" /> 高德地图</div>
        <h4 class="serif">{{ status === 'error' ? '地图加载失败' : '地图待接入' }}</h4>
        <p class="fb-tip">
          后端用的是高德 <b>Web 服务 Key</b>（只查数据、不能渲染瓦片）。<br />
          在 <code>.env</code> 填入 <code>VITE_AMAP_JS_KEY</code> 与 <code>VITE_AMAP_SECURITY_CODE</code> 后重启即可点亮。
        </p>
        <div v-if="activeNodes.length" class="fb-pins">
          <div v-for="(n, i) in activeNodes" :key="i" class="fb-pin">
            <span class="fb-no">{{ i + 1 }}</span>
            <div>
              <div class="fb-name">{{ n.placeName }}</div>
              <div class="fb-meta">{{ n.nodeType }}<template v-if="fmtTime(n)"> · {{ fmtTime(n) }}</template></div>
            </div>
          </div>
        </div>
        <div v-else class="fb-empty">先在左侧对话里说一个出行需求，行程节点会显示在这里。</div>
      </div>
    </div>

    <!-- 顶部浮层：当前天信息 -->
    <div v-if="status === 'ready' && info" class="map-info">
      <div class="mi-top">
        <span class="mi-name">{{ info.name }}</span>
        <button class="mi-x" @click="info = null" aria-label="关闭">×</button>
      </div>
      <div class="mi-sub"><MapPin :size="12" /> {{ info.address }}</div>
      <div class="mi-row"><Layers :size="12" /> {{ info.type }}<span v-if="info.time"> · {{ info.time }}</span></div>
    </div>

    <!-- 底部：天数切换 -->
    <div v-if="dayList.length" class="day-switch">
      <span class="ds-label">Day {{ activeDay + 1 }} · {{ dayList[activeDay]?.day?.theme || '行程分段' }}</span>
      <div class="ds-nav">
        <button class="ds-btn" @click="emit('set-day', activeDay - 1 < 0 ? 0 : activeDay - 1)" aria-label="上一天"><ChevronLeft :size="16" /></button>
        <div class="ds-dots">
          <button v-for="d in dayList" :key="d.i" class="ds-dot" :class="{ on: d.i === activeDay }" @click="emit('set-day', d.i)" :aria-label="`第${d.i + 1}天`"></button>
        </div>
        <button class="ds-btn" @click="emit('set-day', activeDay + 1 > dayList.length - 1 ? dayList.length - 1 : activeDay + 1)" aria-label="下一天"><ChevronRight :size="16" /></button>
      </div>
    </div>

    <!-- 右上:底图切换(卫星 / 标准) -->
    <div v-if="status === 'ready'" class="map-layer-toggle" role="group" aria-label="底图切换">
      <button class="mlt-btn" :class="{ on: baseMode === 'satellite' }" @click="setBaseLayer('satellite')">卫星</button>
      <button class="mlt-btn" :class="{ on: baseMode === 'standard' }" @click="setBaseLayer('standard')">标准</button>
    </div>

    <!-- 右下：缩放 / 复位 -->
    <div class="map-ctrl">
      <button class="mc-btn" @click="recenter" title="回到全部节点"><Crosshair :size="16" /></button>
      <div class="mc-zoom">
        <button class="mc-btn" @click="zoom(-1)" title="缩小"><Minus :size="16" /></button>
        <span class="mc-pct">{{ zoomPct }}%</span>
        <button class="mc-btn" @click="zoom(1)" title="放大"><Plus :size="16" /></button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.map-panel {
  position: relative;
  height: 100%;
  width: 100%;
  overflow: hidden;
  background: var(--paper-raise);
}
.map-mount { position: absolute; inset: 0; }

.map-state {
  position: absolute; inset: 0;
  display: grid; place-content: center; justify-items: center; gap: 10px;
  color: var(--ink-3); font-size: 14px;
}

/* 占位底图：淡网格 + 蓝晒底纹，像一张还没落墨的地图 */
.map-fallback { position: absolute; inset: 0; overflow: auto; }
.fb-grid {
  position: absolute; inset: 0;
  background-image:
    linear-gradient(var(--hair-3) 1px, transparent 1px),
    linear-gradient(90deg, var(--hair-3) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(120% 90% at 60% 40%, #000 55%, transparent 100%);
}
.fb-inner {
  position: relative;
  max-width: 420px;
  margin: clamp(24px, 8vh, 72px) auto 0;
  padding: 26px 26px 30px;
  background: color-mix(in srgb, var(--paper) 92%, transparent);
  backdrop-filter: blur(6px);
  border: 1px solid var(--hair-2);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-2);
}
.fb-badge {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--blue); background: var(--blue-wash);
  border-radius: 999px; padding: 5px 12px; margin-bottom: 12px;
}
.fb-inner h4 { margin: 0 0 8px; font-size: 20px; color: var(--ink); }
.fb-tip { margin: 0 0 16px; font-size: 13px; line-height: 1.7; color: var(--ink-2); }
.fb-tip code { font-family: var(--f-mono); font-size: 12px; background: var(--paper-sink); padding: 1px 6px; border-radius: 5px; color: var(--blue); }
.fb-pins { display: flex; flex-direction: column; gap: 10px; }
.fb-pin { display: flex; align-items: center; gap: 12px; padding: 10px 12px; border: 1px solid var(--hair-2); border-radius: var(--r-md); background: var(--paper); }
.fb-no { flex: none; width: 24px; height: 24px; border-radius: 50%; background: var(--blue); color: #fff; display: grid; place-items: center; font-size: 12px; font-weight: 700; }
.fb-name { font-size: 14px; color: var(--ink); }
.fb-meta { font-size: 12px; color: var(--ink-3); }
.fb-empty { font-size: 13px; color: var(--ink-4); }

/* 顶部信息卡 */
.map-info {
  position: absolute; top: 18px; left: 18px; z-index: 5;
  width: 250px; padding: 14px 16px;
  background: var(--paper); border: 1px solid var(--hair-2);
  border-radius: var(--r-lg); box-shadow: var(--shadow-2);
}
.mi-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.mi-name { font-size: 15px; font-weight: 600; color: var(--ink); }
.mi-x { border: 0; background: none; cursor: pointer; color: var(--ink-4); font-size: 18px; line-height: 1; }
.mi-sub { display: flex; align-items: center; gap: 5px; margin-top: 6px; font-size: 12.5px; color: var(--ink-3); }
.mi-row { display: flex; align-items: center; gap: 5px; margin-top: 4px; font-size: 12.5px; color: var(--blue); }

/* 搜索浮层:地图之上、顶部居中 */
.map-search { position: absolute; top: 18px; left: 50%; transform: translateX(-50%); z-index: 9; width: min(560px, 72%); }
.ms-box { display: flex; align-items: center; gap: 8px; background: var(--paper); border: 1px solid var(--hair-2); border-radius: 999px; padding: 6px 8px 6px 14px; box-shadow: var(--shadow-2); }
.ms-ic { flex: none; color: var(--ink-3); }
.ms-input { flex: 1; min-width: 0; border: 0; outline: none; background: transparent; font-family: var(--f-body); font-size: 14px; color: var(--ink); }
.ms-input::placeholder { color: var(--ink-4); }
.ms-go { flex: none; border: 0; border-radius: 999px; background: var(--blue); color: #fff; font-family: var(--f-body); font-size: 13px; padding: 7px 16px; cursor: pointer; transition: filter var(--t-fast) var(--ease), opacity var(--t-fast) var(--ease); }
.ms-go:hover:not(:disabled) { filter: brightness(1.08); }
.ms-go:disabled { opacity: .6; cursor: default; }
.ms-hint { margin: 8px 6px 0; font-size: 12.5px; color: var(--ink-3); background: color-mix(in srgb, var(--paper) 88%, transparent); padding: 4px 12px; border-radius: 8px; display: inline-block; }
.ms-dd { list-style: none; margin: 8px 0 0; padding: 6px; background: var(--paper); border: 1px solid var(--hair-2); border-radius: var(--r-md); box-shadow: var(--shadow-2); max-height: 288px; overflow: auto; }
.ms-item { display: flex; align-items: baseline; gap: 10px; padding: 8px 12px; border-radius: 8px; cursor: pointer; transition: background var(--t-fast) var(--ease); }
.ms-item:hover { background: var(--blue-wash); }
.ms-name { font-size: 14px; color: var(--ink); }
.ms-region { font-size: 12px; color: var(--ink-4); margin-left: auto; }

/* 景点搜索结果地图钉(高德 Marker HTML,须 :global) */
:global(.xj-spot-pin) {
  width: 18px; height: 18px;
  border-radius: 50% 50% 50% 0;
  background: var(--blue); border: 2px solid #fff;
  transform: rotate(-45deg);
  box-shadow: 0 2px 6px rgba(15, 61, 99, 0.4);
}

/* 右上角底图切换 */
.map-layer-toggle {
  position: absolute; top: 18px; right: 18px; z-index: 9;
  display: inline-flex; padding: 3px;
  background: var(--paper); border: 1px solid var(--hair-2);
  border-radius: 999px; box-shadow: var(--shadow-1);
}
.mlt-btn {
  border: 0; background: transparent; cursor: pointer;
  font-family: var(--f-body); font-size: 12.5px; color: var(--ink-2);
  padding: 5px 14px; border-radius: 999px;
  transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease);
}
.mlt-btn:hover { color: var(--blue); }
.mlt-btn.on { background: var(--blue); color: #fff; }

/* 底部天数切换 */
.day-switch {
  position: absolute; left: 50%; bottom: 22px; transform: translateX(-50%); z-index: 5;
  display: flex; align-items: center; gap: 16px;
  padding: 8px 10px 8px 18px;
  background: var(--paper); border: 1px solid var(--hair-2);
  border-radius: 999px; box-shadow: var(--shadow-2);
}
.ds-label { font-size: 13px; color: var(--ink-2); white-space: nowrap; }
.ds-nav { display: flex; align-items: center; gap: 8px; }
.ds-btn { width: 30px; height: 30px; border-radius: 50%; border: 1px solid var(--hair); background: var(--paper); color: var(--ink-2); cursor: pointer; display: grid; place-items: center; transition: all var(--t-fast) var(--ease); }
.ds-btn:hover { border-color: var(--blue-line); color: var(--blue); }
.ds-dots { display: flex; align-items: center; gap: 6px; }
.ds-dot { width: 8px; height: 8px; border-radius: 50%; border: 0; background: var(--hair); cursor: pointer; padding: 0; transition: all var(--t-base) var(--ease); }
.ds-dot.on { background: var(--blue); width: 22px; border-radius: 999px; }

/* 右下控制 */
.map-ctrl { position: absolute; right: 18px; bottom: 22px; z-index: 5; display: flex; flex-direction: column; align-items: flex-end; gap: 10px; }
.mc-zoom { display: flex; flex-direction: column; align-items: center; background: var(--paper); border: 1px solid var(--hair-2); border-radius: 999px; box-shadow: var(--shadow-1); overflow: hidden; }
.mc-btn { width: 36px; height: 36px; border: 0; background: var(--paper); color: var(--ink-2); cursor: pointer; display: grid; place-items: center; transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease); }
.mc-btn:hover { background: var(--blue-wash); color: var(--blue); }
.mc-pct { font-size: 11px; font-family: var(--f-mono); color: var(--ink-3); padding: 2px 0; border-top: 1px solid var(--hair-3); border-bottom: 1px solid var(--hair-3); width: 100%; text-align: center; }
.map-ctrl > .mc-btn { border-radius: 50%; box-shadow: var(--shadow-1); border: 1px solid var(--hair-2); }

:global(.xj-pin) {
  width: 26px; height: 26px; border-radius: 50%;
  background: var(--blue); color: #fff; border: 2px solid #fff;
  box-shadow: 0 2px 6px rgba(15, 61, 99, 0.4);
  display: grid; place-items: center; font-size: 12px; font-weight: 700;
  font-family: var(--f-body);
}
.spin { animation: xjspin 0.9s linear infinite; }
@keyframes xjspin { to { transform: rotate(360deg); } }

/* 探索态:热门城市点(高德 Marker 内容,须 :global) */
</style>
