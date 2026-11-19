<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useUiStore } from '../stores/ui'
import {
  uploadMedia,
  mediaUrl,
  listMedia,
  trashMedia,
  deleteMedia,
  restoreMedia,
  clearTrashMedia
} from '../api/xingji'
import AppNav from '../components/AppNav.vue'
import AvatarCropper from '../components/AvatarCropper.vue'
import PasswordModal from '../components/PasswordModal.vue'
import ProfileEditModal from '../components/ProfileEditModal.vue'
import {
  ArchiveRestore,
  CalendarDays,
  Film,
  Image as ImageIcon,
  KeyRound,
  Loader2,
  LogOut,
  Pencil,
  Play,
  Plus,
  RotateCcw,
  Trash2,
  X
} from 'lucide-vue-next'

const auth = useAuthStore()
const ui = useUiStore()
const loading = computed(() => auth.profileLoading && !auth.user)

const toast = ref('')
const pwOpen = ref(false)
const editOpen = ref(false)

let toastTimer = null
function flash(msg) {
  toast.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 2600)
}

// ===== 主页只展示：头像 + 名字 + 性别；其余信息收进「编辑资料」弹窗 =====
const genderText = computed(() => {
  const g = auth.user?.gender ?? 0
  return g === 1 ? '男' : g === 2 ? '女' : '保密'
})
const initial = computed(() => (auth.user?.nickname || auth.user?.phone || '旅').slice(0, 1).toUpperCase())

// 导航栏「相册」跳过来时滚到素材区。标志不能在滚动前清:路由的 scrollBehavior 在导航确认后才跑,
// 它读到的必须是 true,才不会用 {top:0} 把这次滚动顶回去。
const galleryEl = ref(null)
function jumpToGallery() {
  nextTick(() => {
    galleryEl.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    setTimeout(() => (ui.galleryJump = false), 300)
  })
}

onMounted(() => {
  if (!auth.user) auth.fetchProfile()
  if (ui.galleryJump) jumpToGallery()
})

watch(() => ui.galleryJump, (v) => v && jumpToGallery())

// ===== 退出登录：二次确认 =====
const confirmOut = ref(false)
function askSignOut() {
  confirmOut.value = true
}
function cancelSignOut() {
  confirmOut.value = false
}
function doSignOut() {
  confirmOut.value = false
  auth.signOut()
}

// ===== 照片与视频（素材库）：列表 / 回收站 / 软删除 / 恢复 =====
const mediaList = ref([])
const trashList = ref([])
const mediaLoading = ref(false)
const galUploading = ref(false)
const filter = ref('all') // all | image | video
const showTrash = ref(false)
const delTarget = ref(null) // 待确认软删除的素材
const galInput = ref(null)

// ===== 时间筛选：年 › 月 › 日，纯前端过滤，与类型叠加 =====
const tfYear = ref(null)
const tfMonth = ref(null)
const tfDay = ref(null)

function mediaDate(m) {
  const s = m.uploadTime || m.createTime
  if (!s) return null
  const d = new Date(String(s).replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? null : d
}
function pickYear(y) {
  tfYear.value = y
  tfMonth.value = null
  tfDay.value = null
}
function pickMonth(mo) {
  tfMonth.value = mo
  tfDay.value = null
}

function tally(list, keyOf, sortDesc) {
  const map = new Map()
  for (const m of list) {
    const k = keyOf(mediaDate(m))
    if (k == null) continue
    map.set(k, (map.get(k) || 0) + 1)
  }
  const facets = [...map.entries()].map(([value, count]) => ({ value, count }))
  return sortDesc ? facets.sort((a, b) => b.value - a.value) : facets.sort((a, b) => a.value - b.value)
}

const typeList = computed(() =>
  filter.value === 'all' ? mediaList.value : mediaList.value.filter((m) => m.mediaType === filter.value)
)
const yearFacets = computed(() => tally(typeList.value, (d) => d && d.getFullYear(), true))
const monthFacets = computed(() =>
  tfYear.value ? tally(typeList.value.filter((m) => mediaDate(m)?.getFullYear() === tfYear.value), (d) => d && d.getMonth() + 1) : []
)
const dayFacets = computed(() =>
  tfYear.value && tfMonth.value
    ? tally(
        typeList.value.filter((m) => {
          const d = mediaDate(m)
          return d && d.getFullYear() === tfYear.value && d.getMonth() + 1 === tfMonth.value
        }),
        (d) => d && d.getDate()
      )
    : []
)

// 关键词搜索或类型切换后,原来选中的年/月/日可能已无照片,自动回退一级
watch([yearFacets, monthFacets, dayFacets], () => {
  if (tfYear.value && !yearFacets.value.some((f) => f.value === tfYear.value)) tfYear.value = null
  if (tfMonth.value && !monthFacets.value.some((f) => f.value === tfMonth.value)) tfMonth.value = null
  if (tfDay.value && !dayFacets.value.some((f) => f.value === tfDay.value)) tfDay.value = null
})

const shown = computed(() =>
  typeList.value.filter((m) => {
    if (!tfYear.value) return true
    const d = mediaDate(m)
    if (!d) return false
    if (d.getFullYear() !== tfYear.value) return false
    if (tfMonth.value && d.getMonth() + 1 !== tfMonth.value) return false
    if (tfDay.value && d.getDate() !== tfDay.value) return false
    return true
  })
)
const trashCount = computed(() => trashList.value.length)

async function loadMedia() {
  mediaLoading.value = true
  try {
    mediaList.value = await listMedia()
    trashList.value = await trashMedia()
  } catch (err) {
    flash(err.message || '素材加载失败')
  } finally {
    mediaLoading.value = false
  }
}

function pickGallery() {
  if (galUploading.value) return
  galInput.value?.click()
}

async function onGalleryFile(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = '' // 允许再次选同一批
  if (!files.length) return
  galUploading.value = true
  let ok = 0
  let fail = ''
  for (const f of files) {
    try {
      const m = await uploadMedia(f)
      mediaList.value = [m, ...mediaList.value]
      ok++
    } catch (err) {
      fail = err.message || '上传失败'
    }
  }
  galUploading.value = false
  if (ok) flash(`已上传 ${ok} 个素材`)
  else if (fail) flash(fail)
}

function askDelete(m) {
  delTarget.value = m
}
function cancelDelete() {
  delTarget.value = null
}
async function confirmDelete() {
  const m = delTarget.value
  if (!m) return
  delTarget.value = null
  try {
    await deleteMedia(m.id)
    mediaList.value = mediaList.value.filter((x) => x.id !== m.id)
    trashList.value = [{ ...m, deleted: 1 }, ...trashList.value]
    flash('已移入回收站，可随时恢复')
  } catch (err) {
    flash(err.message || '删除失败')
  }
}

async function restore(m) {
  try {
    await restoreMedia(m.id)
    trashList.value = trashList.value.filter((x) => x.id !== m.id)
    mediaList.value = [{ ...m, deleted: 0 }, ...mediaList.value]
    flash('已恢复到相册')
  } catch (err) {
    flash(err.message || '恢复失败')
  }
}

function onThumbErr(m) {
  m._broken = true
}

// 回收站保留天数，与后端 xingji.media.retention-days 默认值一致
const RETAIN_DAYS = 30
function ttlText(m) {
  const base = new Date(m.updateTime || m.uploadTime || Date.now()).getTime()
  const days = Math.ceil((base + RETAIN_DAYS * 86400000 - Date.now()) / 86400000)
  return days > 0 ? `剩 ${days} 天` : '即将清理'
}

// 清空回收站（彻底删除，二次确认）
const clearConfirm = ref(false)
async function doClearTrash() {
  clearConfirm.value = false
  try {
    await clearTrashMedia()
    trashList.value = []
    flash('已清空回收站')
  } catch (err) {
    flash(err.message || '清空失败')
  }
}

// ===== 点击放大预览 =====
const preview = ref(null)
const avatarZoom = ref(false)
// 取景永远从后端记的原图开始（老账号没记过就用当前头像），重取不会越裁越小
const avatarSrc = computed(() => auth.user?.avatarSrc || auth.user?.avatar || '')
function openPreview(m) {
  if (m._broken) return
  preview.value = m
}
function onEsc(e) {
  if (e.key === 'Escape') {
    preview.value = null
    avatarZoom.value = false
  }
}
// 取景层裁好的头像直接写回资料（这条路径上没有「保存」按钮），源图一并存回去供换设备重取
async function applyAvatar(url) {
  const u = auth.user || {}
  const ok = await auth.saveProfile({
    nickname: u.nickname,
    email: u.email || undefined,
    avatar: url,
    avatarSrc: avatarSrc.value || undefined,
    gender: u.gender ?? 0
  })
  avatarZoom.value = false
  flash(ok ? '头像已更新' : auth.error || '头像保存失败')
}

onMounted(() => {
  if (auth.user) loadMedia()
  document.addEventListener('keydown', onEsc)
})
onBeforeUnmount(() => document.removeEventListener('keydown', onEsc))
watch(() => auth.user, (u) => u && loadMedia())
</script>

<template>
  <div class="page">
    <AppNav />

    <main class="wrap body">
      <div class="head">
        <span class="eyebrow reveal" v-reveal>个人主页</span>
      </div>

      <!-- 用户主页头部：头像 + 名字 + 性别 + 编辑入口 -->
      <section class="home reveal" v-reveal="80">
        <div class="home-top">
          <button
            v-if="auth.user?.avatar"
            class="big-av zoomable"
            type="button"
            title="点击调整头像取景"
            aria-label="调整头像取景"
            @click="avatarZoom = true"
          >
            <img :src="auth.user.avatar" :alt="auth.user?.nickname" />
          </button>
          <div v-else class="big-av">{{ initial }}</div>
          <div class="home-id">
            <div class="home-nameline">
              <h1 class="serif home-name">{{ auth.user?.nickname || (loading ? '加载中…' : '旅人') }}</h1>
              <span class="g-chip" :class="{ unset: (auth.user?.gender ?? 0) === 0 }">{{ genderText }}</span>
            </div>
          </div>
          <div class="home-acts">
            <button class="btn edit" @click="editOpen = true">
              <Pencil :size="15" /> 编辑资料
            </button>
          </div>
        </div>
        <div class="home-foot">
          <button class="lnk" @click="pwOpen = true">
            <KeyRound :size="14" /> 修改密码
          </button>
          <span class="dot">·</span>
          <button class="lnk" @click="askSignOut">
            <LogOut :size="14" /> 退出登录
          </button>
        </div>
      </section>

      <!-- 照片与视频（素材库） -->
      <section ref="galleryEl" class="gallery reveal" v-reveal="160">
        <header class="gal-head">
          <div class="gal-title">
            <h3 class="serif">{{ showTrash ? '回收站' : '照片与视频' }}</h3>
            <p v-if="!showTrash" class="gal-sub">上传到行迹的素材都收在这里。删除会先进回收站，随时可以恢复。</p>
          </div>
          <div class="gal-tools">
            <div v-if="!showTrash" class="seg" role="tablist">
              <button
                class="seg-btn"
                :class="{ on: filter === 'all' }"
                @click="filter = 'all'"
                role="tab"
                :aria-selected="filter === 'all'"
              >全部</button>
              <button
                class="seg-btn"
                :class="{ on: filter === 'image' }"
                @click="filter = 'image'"
                role="tab"
                :aria-selected="filter === 'image'"
              ><ImageIcon :size="13" /> 照片</button>
              <button
                class="seg-btn"
                :class="{ on: filter === 'video' }"
                @click="filter = 'video'"
                role="tab"
                :aria-selected="filter === 'video'"
              ><Film :size="13" /> 视频</button>
            </div>
            <button v-if="!showTrash" class="btn gal-up" :disabled="galUploading" @click="pickGallery">
              <Loader2 v-if="galUploading" :size="15" class="spin" />
              <Plus v-else :size="15" />
              上传
            </button>
            <button v-if="showTrash" class="btn danger" :disabled="!trashCount" @click="clearConfirm = true">
              <Trash2 :size="15" /> 全部清除
            </button>
            <button class="btn btn-ghost trash-toggle" @click="showTrash = !showTrash" :aria-pressed="showTrash">
              <ArchiveRestore :size="15" /> {{ showTrash ? '返回相册' : '回收站' }}
              <span v-if="trashCount && !showTrash" class="cnt">{{ trashCount }}</span>
            </button>
            <input ref="galInput" type="file" accept="image/*,video/*" multiple hidden @change="onGalleryFile" />
          </div>
        </header>

        <div v-if="!showTrash" class="gal-filters">
          <div v-if="yearFacets.length" class="time-filter">
            <div class="tf-row">
              <span class="tf-label"><CalendarDays :size="13" /> 时间</span>
              <button class="tf-chip" :class="{ on: !tfYear }" :aria-pressed="!tfYear" @click="pickYear(null)">全部</button>
              <button
                v-for="y in yearFacets"
                :key="y.value"
                class="tf-chip"
                :class="{ on: tfYear === y.value }"
                :aria-pressed="tfYear === y.value"
                @click="pickYear(y.value)"
              >{{ y.value }} 年 <span class="tf-n">{{ y.count }}</span></button>
            </div>
            <div v-if="tfYear && monthFacets.length" class="tf-row">
              <span class="tf-label">月份</span>
              <button class="tf-chip" :class="{ on: !tfMonth }" :aria-pressed="!tfMonth" @click="pickMonth(null)">全年</button>
              <button
                v-for="mo in monthFacets"
                :key="mo.value"
                class="tf-chip"
                :class="{ on: tfMonth === mo.value }"
                :aria-pressed="tfMonth === mo.value"
                @click="pickMonth(mo.value)"
              >{{ mo.value }} 月 <span class="tf-n">{{ mo.count }}</span></button>
            </div>
            <div v-if="tfMonth && dayFacets.length" class="tf-row">
              <span class="tf-label">日期</span>
              <button class="tf-chip" :class="{ on: !tfDay }" :aria-pressed="!tfDay" @click="tfDay = null">全月</button>
              <button
                v-for="d in dayFacets"
                :key="d.value"
                class="tf-chip"
                :class="{ on: tfDay === d.value }"
                :aria-pressed="tfDay === d.value"
                @click="tfDay = d.value"
              >{{ d.value }} 日</button>
            </div>
          </div>
        </div>

        <div v-if="showTrash" class="trash-panel">
          <p v-if="!trashList.length" class="trash-empty">回收站是空的。</p>
          <div v-else class="gal-grid trash-grid">
            <figure v-for="m in trashList" :key="'t' + m.id" class="tile trashed" @click="openPreview(m)">
              <div class="tile-media">
                <img
                  v-if="m.mediaType === 'image' && !m._broken"
                  :src="mediaUrl(m.filePath)"
                  :alt="'回收站素材 ' + m.id"
                  loading="lazy"
                  @error="onThumbErr(m)"
                />
                <video
                  v-else-if="m.mediaType === 'video' && !m._broken"
                  :src="mediaUrl(m.filePath)"
                  preload="metadata"
                  muted
                  playsinline
                ></video>
                <div v-else class="tile-broken"><ImageIcon :size="22" /><span>无法预览</span></div>
                <span class="tile-ttl mono">{{ ttlText(m) }}</span>
              </div>
              <button class="tile-restore" type="button" @click.stop="restore(m)">
                <RotateCcw :size="14" /> 恢复
              </button>
            </figure>
          </div>
        </div>

        <div v-else-if="mediaLoading" class="gal-grid">
          <div v-for="n in 4" :key="'sk' + n" class="tile skeleton"></div>
        </div>

        <p v-else-if="!shown.length" class="gal-empty">
          <ImageIcon :size="18" />
          {{
            tfYear
              ? '这段时间还没有素材，换个年份或月份看看。'
              : filter === 'image'
                ? '还没有照片，点「上传」添加第一张吧。'
                : filter === 'video'
                  ? '还没有视频，点「上传」添加第一个吧。'
                  : '这里还是空的，点「上传」添加照片或视频。'
          }}
        </p>

        <TransitionGroup v-else name="grid" tag="div" class="gal-grid">
          <figure v-for="m in shown" :key="m.id" class="tile" @click="openPreview(m)">
            <div class="tile-media">
              <img
                v-if="m.mediaType === 'image' && !m._broken"
                :src="mediaUrl(m.filePath)"
                :alt="'照片 ' + m.id"
                loading="lazy"
                @error="onThumbErr(m)"
              />
              <video
                v-else-if="m.mediaType === 'video' && !m._broken"
                :src="mediaUrl(m.filePath)"
                preload="metadata"
                muted
                playsinline
              ></video>
              <div v-else class="tile-broken"><ImageIcon :size="22" /><span>无法预览</span></div>
              <span v-if="m.mediaType === 'video'" class="tile-play"><Play :size="20" :fill="'currentColor'" /></span>
            </div>
            <button class="tile-del" type="button" title="移入回收站" aria-label="移入回收站" @click.stop="askDelete(m)">
              <Trash2 :size="16" />
            </button>
          </figure>
        </TransitionGroup>
      </section>
    </main>

    <!-- 点击放大预览 -->
    <Transition name="lb">
      <div v-if="preview" class="lightbox" role="dialog" aria-modal="true" aria-label="照片预览" @click.self="preview = null">
        <button class="lb-close" type="button" aria-label="关闭预览" @click="preview = null">
          <X :size="20" />
        </button>
        <img v-if="preview.mediaType === 'image'" :src="mediaUrl(preview.filePath)" :alt="preview.title || '照片预览'" />
        <video v-else :src="mediaUrl(preview.filePath)" controls playsinline></video>
      </div>
    </Transition>

    <!-- 头像取景层：整图铺底 + 圆框取景，确定后直接存进资料 -->
    <AvatarCropper
      v-if="avatarZoom && avatarSrc"
      :src="avatarSrc"
      :alt="`${auth.user?.nickname || '旅人'}的头像`"
      @applied="applyAvatar"
      @cancel="avatarZoom = false"
    />

    <!-- 轻提示 -->
    <transition name="toast">
      <div v-if="toast" class="toast">{{ toast }}</div>
    </transition>

    <!-- 退出登录二次确认 -->
    <Teleport to="body">
      <transition name="confirm">
        <div v-if="confirmOut" class="cfm-overlay" role="alertdialog" aria-modal="true">
          <div class="cfm-scrim" @click="cancelSignOut"></div>
          <div class="cfm-card">
            <span class="cfm-ic"><LogOut :size="20" /></span>
            <h4 class="serif">确定退出登录吗？</h4>
            <p>退出后需要重新登录才能查看你的行程。当前未保存的改动会丢失。</p>
            <div class="cfm-acts">
              <button class="btn btn-ghost" @click="cancelSignOut">取消</button>
              <button class="btn" @click="doSignOut">退出登录</button>
            </div>
          </div>
        </div>
      </transition>
    </Teleport>

    <!-- 软删除素材二次确认 -->
    <Teleport to="body">
      <transition name="confirm">
        <div v-if="delTarget" class="cfm-overlay" role="alertdialog" aria-modal="true">
          <div class="cfm-scrim" @click="cancelDelete"></div>
          <div class="cfm-card">
            <span class="cfm-ic danger"><Trash2 :size="20" /></span>
            <h4 class="serif">移入回收站？</h4>
            <p>这张{{ delTarget.mediaType === 'image' ? '照片' : '视频' }}会被软删除并放进回收站，之后仍可随时恢复。</p>
            <div class="cfm-acts">
              <button class="btn btn-ghost" @click="cancelDelete">取消</button>
              <button class="btn danger" @click="confirmDelete">移入回收站</button>
            </div>
          </div>
        </div>
      </transition>
    </Teleport>

    <!-- 清空回收站二次确认 -->
    <Teleport to="body">
      <transition name="confirm">
        <div v-if="clearConfirm" class="cfm-overlay" role="alertdialog" aria-modal="true">
          <div class="cfm-scrim" @click="clearConfirm = false"></div>
          <div class="cfm-card">
            <span class="cfm-ic danger"><Trash2 :size="20" /></span>
            <h4 class="serif">清空回收站？</h4>
            <p>回收站里的照片和视频会被彻底删除，无法恢复。</p>
            <div class="cfm-acts">
              <button class="btn btn-ghost" @click="clearConfirm = false">取消</button>
              <button class="btn danger" @click="doClearTrash">全部清除</button>
            </div>
          </div>
        </div>
      </transition>
    </Teleport>

    <ProfileEditModal :open="editOpen" :user="auth.user" @close="editOpen = false" @saved="flash('资料已保存')" />
    <PasswordModal :open="pwOpen" @close="pwOpen = false" @success="flash('密码已修改')" />
  </div>
</template>

<style scoped>
.page { min-height: 100vh; }
.body { padding-top: clamp(36px, 7vh, 84px); padding-bottom: 100px; }
.head { display: flex; flex-direction: column; gap: 12px; margin-bottom: 28px; }
.mono { font-family: var(--f-mono); }

/* ===== 用户主页头部卡 ===== */
.home {
  background: var(--paper);
  border: 1px solid var(--hair-2);
  border-radius: var(--r-lg);
  padding: 30px 32px 20px;
  box-shadow: var(--shadow-1);
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.home-top { display: flex; align-items: center; gap: 22px; flex-wrap: wrap; }
.big-av {
  width: 92px; height: 92px; border-radius: 50%;
  background: var(--blue-wash); color: var(--blue);
  display: grid; place-items: center; font-size: 38px; font-weight: 700;
  overflow: hidden; border: 1px solid var(--blue-line); flex: none;
}
.big-av img { width: 100%; height: 100%; object-fit: cover; }
button.big-av { padding: 0; font: inherit; cursor: zoom-in; transition: box-shadow var(--t-base) var(--ease), transform var(--t-fast) var(--ease); }
button.big-av:hover { box-shadow: 0 0 0 3px var(--blue-wash), var(--shadow-1); }
button.big-av:active { transform: scale(0.97); }
.home-id { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.home-nameline { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.home-name { font-size: clamp(26px, 3.4vw, 40px); font-weight: 600; color: var(--ink); line-height: 1.15; }
.g-chip {
  display: inline-flex; align-items: center;
  font-size: 12.5px; font-weight: 600;
  padding: 4px 13px; border-radius: 999px;
  background: var(--blue-wash); color: var(--blue); border: 1px solid var(--blue-line);
}
.g-chip.unset { background: var(--paper-sink); color: var(--ink-3); border-color: var(--hair-2); }
.home-acts { margin-left: auto; }
.edit { padding: 11px 20px; font-size: 14px; }
.home-foot {
  display: flex; align-items: center; gap: 10px;
  padding-top: 14px; border-top: 1px solid var(--hair-3);
}
.lnk {
  display: inline-flex; align-items: center; gap: 6px;
  border: 0; background: none; cursor: pointer; padding: 4px 2px;
  font-family: var(--f-body); font-size: 13px; color: var(--ink-3);
  transition: color var(--t-base) var(--ease);
}
.lnk:hover { color: var(--blue); }
.home-foot .dot { color: var(--ink-4); font-size: 13px; }

/* ===== 照片与视频（素材库） ===== */
.gallery {
  margin-top: 24px;
  scroll-margin-top: 88px;
  background: var(--paper);
  border: 1px solid var(--hair-2);
  border-radius: var(--r-lg);
  padding: 28px 30px 30px;
  box-shadow: var(--shadow-1);
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.gal-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
}
.gal-title { display: flex; flex-direction: column; gap: 6px; }
.gal-title h3 { font-size: 20px; color: var(--ink); }
.gal-sub { color: var(--ink-3); font-size: 13.5px; max-width: 56ch; }
.gal-tools { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }

.seg {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  background: var(--paper-sink);
  border: 1px solid var(--hair-2);
  border-radius: 999px;
}
.seg-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 0;
  background: transparent;
  color: var(--ink-3);
  font-family: var(--f-body);
  font-size: 12.5px;
  font-weight: 600;
  padding: 6px 13px;
  border-radius: 999px;
  cursor: pointer;
  transition: color var(--t-base) var(--ease), background var(--t-base) var(--ease),
    box-shadow var(--t-base) var(--ease), transform var(--t-fast) var(--ease);
}
.seg-btn:hover { color: var(--ink); }
.seg-btn.on {
  background: var(--paper);
  color: var(--blue);
  box-shadow: var(--shadow-1);
}
.seg-btn:active { transform: scale(0.96); }

.gal-up { padding: 9px 16px; font-size: 13px; }
.gal-up:disabled { opacity: 0.6; cursor: progress; }

.gal-filters { display: flex; flex-direction: column; gap: 11px; }
.time-filter { display: flex; flex-direction: column; gap: 7px; }
.tf-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.tf-label {
  display: inline-flex; align-items: center; gap: 5px;
  min-width: 44px; margin-right: 2px;
  font-size: 12px; color: var(--ink-4);
}
.tf-chip {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 5px 11px;
  border: 1px solid var(--hair-2);
  border-radius: 999px;
  background: var(--paper);
  color: var(--ink-3);
  font-family: var(--f-body);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: color var(--t-base) var(--ease), background var(--t-base) var(--ease),
    border-color var(--t-base) var(--ease), transform var(--t-fast) var(--ease);
}
.tf-chip:hover { color: var(--ink); border-color: var(--hair); }
.tf-chip.on {
  background: var(--blue-wash);
  border-color: var(--blue-line);
  color: var(--blue);
}
.tf-chip:active { transform: scale(0.96); }
.tf-n { font-family: var(--f-mono); font-size: 10.5px; font-weight: 500; opacity: 0.72; }
.trash-toggle { padding: 9px 14px; font-size: 13px; }
.trash-toggle .cnt {
  min-width: 18px; height: 18px; padding: 0 5px;
  display: inline-grid; place-items: center;
  font-family: var(--f-mono); font-size: 11px;
  background: var(--blue-wash); color: var(--blue);
  border-radius: 999px;
}

.gal-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
  gap: 16px;
}
.tile {
  position: relative;
  margin: 0;
  background: var(--paper-raise);
  border: 1px solid var(--hair-2);
  border-radius: var(--r-md);
  overflow: hidden;
  transition: transform var(--t-base) var(--ease), box-shadow var(--t-base) var(--ease),
    border-color var(--t-base) var(--ease);
}
.tile:hover { transform: translateY(-3px); box-shadow: var(--shadow-2); border-color: var(--hair); }
.tile { cursor: zoom-in; }
.tile-media {
  position: relative;
  aspect-ratio: 4 / 3;
  background: var(--paper-sink);
  overflow: hidden;
}
.tile-media img,
.tile-media video { width: 100%; height: 100%; object-fit: cover; display: block; }
.tile-broken {
  width: 100%; height: 100%;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px;
  color: var(--ink-4); font-size: 12px;
}
.tile-play {
  position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);
  width: 44px; height: 44px; border-radius: 50%;
  display: grid; place-items: center;
  background: rgba(15, 61, 99, 0.62); color: #fff;
  pointer-events: none;
  transition: transform var(--t-base) var(--ease), background var(--t-base) var(--ease);
}
.tile-ttl {
  position: absolute; top: 8px; left: 8px;
  padding: 3px 8px; border-radius: 999px;
  background: rgba(10, 25, 40, 0.66); color: #fff;
  font-size: 11px; letter-spacing: 0.02em;
  pointer-events: none;
}
.tile:hover .tile-play { transform: translate(-50%, -50%) scale(1.08); background: rgba(15, 61, 99, 0.78); }
.tile-meta {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 9px 11px;
  font-size: 12px; color: var(--ink-3);
  border-top: 1px solid var(--hair-2);
}
.t-label { display: flex; align-items: center; gap: 6px; min-width: 0; }
.t-region {
  flex: none;
  padding: 2px 7px; border-radius: 999px;
  background: var(--blue-wash); color: var(--blue);
  font-size: 11px; font-weight: 600;
}
.t-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; color: var(--ink-2); }

/* 点击放大预览浮层 */
.lightbox {
  position: fixed; inset: 0; z-index: 70;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px;
  padding: 48px 40px 36px;
  background: rgba(10, 25, 40, 0.82);
  backdrop-filter: blur(6px);
}
.lightbox img,
.lightbox video {
  max-width: min(1200px, 92vw); max-height: 80vh;
  object-fit: contain;
  border-radius: var(--r-md);
  box-shadow: var(--shadow-2);
  background: #000;
}
.lb-close {
  position: absolute; top: 18px; right: 22px;
  width: 40px; height: 40px; border-radius: 50%;
  display: grid; place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.25);
  background: rgba(255, 255, 255, 0.12); color: #fff;
  cursor: pointer; padding: 0;
  transition: background var(--t-base) var(--ease), transform var(--t-fast) var(--ease);
}
.lb-close:hover { background: rgba(255, 255, 255, 0.26); }
.lb-close:active { transform: scale(0.92); }
.lb-cap { display: flex; align-items: center; gap: 8px; color: rgba(255, 255, 255, 0.88); font-size: 13.5px; }
.lb-cap .t-region { background: rgba(255, 255, 255, 0.16); color: #fff; }
.lb-enter-active,
.lb-leave-active { transition: opacity var(--t-base) var(--ease); }
.lb-enter-from,
.lb-leave-to { opacity: 0; }

.tile-del {
  position: absolute; top: 8px; right: 8px;
  width: 30px; height: 30px; border-radius: 50%;
  display: grid; place-items: center;
  background: rgba(252, 252, 253, 0.92); color: var(--seal);
  border: 1px solid var(--seal-line);
  cursor: pointer; padding: 0;
  opacity: 0; transform: translateY(-5px) scale(0.9);
  transition: opacity var(--t-base) var(--ease), transform var(--t-base) var(--ease),
    background var(--t-base) var(--ease), color var(--t-base) var(--ease);
}
.tile:hover .tile-del,
.tile-del:focus-visible { opacity: 1; transform: none; }
.tile-del:hover { background: var(--seal); color: #fff; }
.tile-del:active { transform: scale(0.92); }

/* 回收站里的素材：压暗 + 居中恢复按钮 */
.tile.trashed .tile-media { filter: grayscale(0.4); opacity: 0.82; }
.tile-restore {
  position: absolute; top: 8px; right: 8px;
  display: inline-flex; align-items: center; gap: 5px;
  padding: 5px 10px; border-radius: 999px;
  background: var(--paper); color: var(--blue);
  border: 1px solid var(--blue-line);
  font-family: var(--f-body); font-size: 12px; font-weight: 600;
  cursor: pointer;
  box-shadow: var(--shadow-1);
  transition: transform var(--t-fast) var(--ease), background var(--t-base) var(--ease), color var(--t-base) var(--ease);
}
.tile-restore:hover { background: var(--blue); color: #fff; }
.tile-restore:active { transform: scale(0.94); }

.skeleton {
  aspect-ratio: 4 / 3;
  background: var(--paper-sink);
  animation: skpulse 1.2s var(--ease-soft) infinite;
}
@keyframes skpulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }

.gal-empty {
  display: flex; align-items: center; gap: 9px;
  padding: 26px 20px;
  color: var(--ink-3); font-size: 13.5px;
  background: var(--paper-raise);
  border: 1px dashed var(--hair);
  border-radius: var(--r-md);
}

.trash-panel { display: flex; flex-direction: column; gap: 4px; }
.trash-empty { color: var(--ink-4); font-size: 13px; }
.trash-grid { margin-top: 0; }

/* TransitionGroup：网格 FLIP 位移 + 淡入淡出 */
.grid-move { transition: transform var(--t-morph) var(--ease); }
.grid-enter-active { transition: opacity var(--t-base) var(--ease), transform var(--t-base) var(--ease); }
.grid-leave-active { transition: opacity var(--t-base) var(--ease), transform var(--t-base) var(--ease); position: absolute; }
.grid-enter-from { opacity: 0; transform: scale(0.9); }
.grid-leave-to { opacity: 0; transform: scale(0.86); }

/* toast */
.toast {
  position: fixed; left: 50%; bottom: 34px; transform: translateX(-50%);
  background: var(--ink); color: var(--paper);
  font-size: 14px; padding: 12px 20px; border-radius: 999px;
  box-shadow: var(--shadow-2); z-index: 80;
}
.toast-enter-active, .toast-leave-active { transition: opacity var(--t-base) var(--ease), transform var(--t-base) var(--ease); }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateX(-50%) translateY(12px); }

/* 确认弹窗（退出 / 软删除共用） */
.cfm-overlay {
  position: fixed; inset: 0; z-index: 90;
  display: grid; place-items: center; padding: 24px;
}
.cfm-scrim { position: absolute; inset: 0; background: rgba(21, 23, 27, 0.42); backdrop-filter: blur(2px); }
.cfm-card {
  position: relative; z-index: 1; width: min(400px, 100%);
  background: var(--paper); border: 1px solid var(--hair-2);
  border-radius: var(--r-lg); box-shadow: var(--shadow-lift);
  padding: 28px 26px 22px; display: flex; flex-direction: column; gap: 12px; text-align: center;
}
.cfm-ic {
  width: 46px; height: 46px; border-radius: 50%; margin: 0 auto;
  background: var(--blue-wash); color: var(--blue);
  display: grid; place-items: center;
}
.cfm-ic.danger { background: var(--seal-wash); color: var(--seal); }
.cfm-card h4 { font-size: 20px; color: var(--ink); margin: 2px 0 0; }
.cfm-card p { font-size: 13.5px; color: var(--ink-3); line-height: 1.6; margin: 0; }
.cfm-acts { display: flex; justify-content: center; gap: 12px; margin-top: 12px; }
.cfm-acts .btn { min-width: 96px; justify-content: center; }
.btn.danger { --btn-bg: var(--seal); --btn-fg: #fff; }
.btn.danger:hover { box-shadow: 0 18px 40px -12px rgba(177, 52, 42, 0.4); }

.confirm-enter-active { transition: opacity var(--t-base) var(--ease); }
.confirm-leave-active { transition: opacity var(--t-fast) var(--ease-soft); }
.confirm-enter-from, .confirm-leave-to { opacity: 0; }
.confirm-enter-active .cfm-card { transition: transform var(--t-slow) var(--ease), opacity var(--t-base) var(--ease); }
.confirm-leave-active .cfm-card { transition: transform var(--t-fast) var(--ease-soft), opacity var(--t-fast) var(--ease-soft); }
.confirm-enter-from .cfm-card { transform: translateY(16px) scale(0.96); opacity: 0; }
.confirm-leave-to .cfm-card { transform: translateY(8px) scale(0.98); opacity: 0; }

.spin { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 640px) {
  .home-top { gap: 16px; }
  .home-acts { margin-left: 0; width: 100%; }
  .edit { width: 100%; justify-content: center; }
}
</style>
