<script setup>
import { reactive, ref, watch, nextTick, computed } from 'vue'
import { onClickOutside, onKeyStroke } from '@vueuse/core'
import { useAuthStore } from '../stores/auth'
import { uploadMedia, mediaUrl } from '../api/xingji'
import FieldInput from './FieldInput.vue'
import AvatarCropper from './AvatarCropper.vue'
import { X, Loader2, Camera, Upload, Crop } from 'lucide-vue-next'

// 编辑资料弹窗：昵称 / 性别 / 邮箱 / 头像。手机号是登录身份，不在此改。
// open 由父级控制；保存走 auth.saveProfile，成功后回写全局 user，主页即时更新。
const props = defineProps({
  open: { type: Boolean, default: false },
  user: { type: Object, default: null }
})
const emit = defineEmits(['close', 'saved'])

const auth = useAuthStore()
const panel = ref(null)
const busy = ref(false)
const uploading = ref(false)
const fileInput = ref(null)
const avZoom = ref(false)

const form = reactive({ nickname: '', email: '', avatar: '', gender: 0 })
const errors = reactive({ nickname: '', email: '', form: '' })
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const GENDERS = [
  { v: 0, label: '保密' },
  { v: 1, label: '男' },
  { v: 2, label: '女' }
]

function hydrate() {
  const u = props.user || {}
  form.nickname = u.nickname || ''
  form.email = u.email || ''
  form.avatar = u.avatar || ''
  form.gender = u.gender ?? 0
  cropBase.value = ''
  croppedUrl.value = ''
  errors.nickname = errors.email = errors.form = ''
}

const dirty = computed(() => {
  const u = props.user || {}
  return (
    form.nickname !== (u.nickname || '') ||
    form.email !== (u.email || '') ||
    form.avatar !== (u.avatar || '') ||
    form.gender !== (u.gender ?? 0)
  )
})

function close() {
  if (busy.value) return
  emit('close')
}

function validate() {
  errors.nickname = !form.nickname ? '请输入昵称' : form.nickname.length > 20 ? '昵称最多 20 字' : ''
  errors.email = form.email && !EMAIL_RE.test(form.email) ? '邮箱格式不正确' : ''
  return !errors.nickname && !errors.email
}

async function submit() {
  errors.form = ''
  if (!validate() || !dirty.value) return
  busy.value = true
  const u = props.user || {}
  const dto = {
    nickname: form.nickname,
    email: form.email || undefined,
    avatar: form.avatar || undefined,
    gender: form.gender
  }
  // 取景重取 → 记下源图；换成新图（上传或粘链接）→ 新图就是源图；头像没动 → 不碰这列
  if (croppedUrl.value && form.avatar === croppedUrl.value) dto.avatarSrc = cropBase.value || undefined
  else if (form.avatar !== (u.avatar || '')) dto.avatarSrc = form.avatar || undefined
  const ok = await auth.saveProfile(dto)
  busy.value = false
  if (ok) {
    emit('saved')
    emit('close')
  } else {
    errors.form = auth.error || '保存失败'
  }
}

// 头像上传：/api/media/upload?purpose=avatar，只存文件不进相册，成功后把 /uploads URL 填进 avatar
function pick() {
  if (uploading.value) return
  fileInput.value?.click()
}
// 点头像圆标：已有头像就进取景层重取，没有才走上传；换头像另有「上传头像」按钮
function onAvatarClick() {
  if (form.avatar) avZoom.value = true
  else pick()
}
// 取景源：优先用本次弹窗已定的源，其次后端记的原图，最后才是当前头像
const cropBase = ref('')
const croppedUrl = ref('')
const cropSrc = computed(() => cropBase.value || props.user?.avatarSrc || form.avatar)
// 裁出来的新头像先填进表单，点「保存」才真正写库；源图不变，重取不会越裁越小
function onCropped(url) {
  cropBase.value = cropBase.value || cropSrc.value
  croppedUrl.value = url
  form.avatar = url
  avZoom.value = false
}
async function onFile(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  const types = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']
  if (!types.includes(file.type)) {
    errors.form = '只支持 JPG / PNG / WEBP / GIF'
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    errors.form = '图片不能超过 10MB'
    return
  }
  uploading.value = true
  errors.form = ''
  try {
    const media = await uploadMedia(file, 'avatar')
    form.avatar = mediaUrl(media.filePath)
  } catch (err) {
    errors.form = err.message || '上传失败'
  } finally {
    uploading.value = false
  }
}

const initial = computed(() => (form.nickname || '旅').slice(0, 1).toUpperCase())

watch(
  () => props.open,
  (open) => {
    if (open) {
      hydrate()
      document.body.style.overflow = 'hidden'
      nextTick(() => panel.value?.querySelector('input')?.focus())
    } else {
      document.body.style.overflow = ''
      avZoom.value = false
    }
  }
)

onClickOutside(panel, () => {
  if (props.open && !avZoom.value) close()
})
onKeyStroke('Escape', () => {
  if (!props.open) return
  if (avZoom.value) avZoom.value = false
  else close()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="pm">
      <div v-if="open" class="overlay" role="dialog" aria-modal="true" aria-label="编辑资料">
        <div class="scrim" @click="close"></div>
        <div class="card" ref="panel">
          <button class="x" aria-label="关闭" @click="close"><X :size="18" /></button>
          <h3 class="serif">编辑资料</h3>
          <p class="sub">改昵称、性别、邮箱或换个头像。手机号是登录身份，不能改。</p>

          <form class="fields" @submit.prevent="submit" novalidate>
            <div class="av-row">
              <button
                class="av-prev"
                type="button"
                :class="{ busy: uploading }"
                @click="onAvatarClick"
                :aria-label="form.avatar ? '重新取景调整头像' : '上传头像'"
                :title="form.avatar ? '点击重新取景' : '点击上传头像'"
              >
                <img v-if="form.avatar" :src="form.avatar" alt="头像预览" />
                <template v-else>{{ initial }}</template>
                <span class="av-cam"><Crop v-if="form.avatar" :size="16" /><Camera v-else :size="16" /></span>
                <span v-if="uploading" class="av-load"><Loader2 :size="18" class="spin" /></span>
              </button>
              <div class="av-edit">
                <button class="btn btn-ghost up" type="button" :disabled="uploading" @click="pick">
                  <Loader2 v-if="uploading" :size="15" class="spin" />
                  <Upload v-else :size="15" />
                  {{ uploading ? '上传中…' : '上传头像' }}
                </button>
              </div>
              <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp,image/gif" hidden @change="onFile" />
            </div>

            <FieldInput v-model="form.nickname" label="昵称" :error="errors.nickname" :maxlength="20" autocomplete="nickname" />

            <div class="g-field">
              <span class="g-lab">性别</span>
              <div class="seg" role="radiogroup" aria-label="性别">
                <button
                  v-for="g in GENDERS"
                  :key="g.v"
                  type="button"
                  class="seg-btn"
                  :class="{ on: form.gender === g.v }"
                  role="radio"
                  :aria-checked="form.gender === g.v"
                  @click="form.gender = g.v"
                >{{ g.label }}</button>
              </div>
            </div>

            <FieldInput v-model="form.email" label="邮箱（选填）" type="email" :error="errors.email" autocomplete="email" />

            <Transition name="fade-up">
              <p v-if="errors.form" class="form-err">{{ errors.form }}</p>
            </Transition>

            <div class="acts">
              <button class="btn btn-ghost" type="button" @click="close">取消</button>
              <button class="btn submit" type="submit" :disabled="busy || !dirty">
                <Loader2 v-if="busy" :size="16" class="spin" />
                <span>{{ busy ? '保存中…' : '保存' }}</span>
              </button>
            </div>
          </form>
        </div>
      </div>
    </Transition>

    <!-- 头像取景层：整图铺底 + 圆形取景框，拖动选区，确定后裁出来填进表单 -->
    <Transition name="az">
      <AvatarCropper
        v-if="avZoom && cropSrc"
        :src="cropSrc"
        :alt="`${form.nickname || '旅人'}的头像`"
        @applied="onCropped"
        @cancel="avZoom = false"
      />
    </Transition>
  </Teleport>
</template>

<style scoped>
.overlay { position: fixed; inset: 0; z-index: 96; display: grid; place-items: center; padding: 24px; }
.scrim { position: absolute; inset: 0; background: rgba(21, 23, 27, 0.42); backdrop-filter: blur(2px); }
.card {
  position: relative; z-index: 1; width: min(460px, 100%);
  background: var(--paper); border: 1px solid var(--hair-2);
  border-radius: var(--r-lg); box-shadow: var(--shadow-lift);
  padding: 30px 28px 26px; display: flex; flex-direction: column; gap: 8px;
  max-height: calc(100vh - 48px); overflow-y: auto;
}
.x {
  position: absolute; top: 14px; right: 14px; width: 32px; height: 32px;
  border-radius: 50%; border: 0; background: none; color: var(--ink-3);
  cursor: pointer; display: grid; place-items: center;
  transition: color var(--t-base) var(--ease), background var(--t-base) var(--ease), transform var(--t-fast) var(--ease);
}
.x:hover { color: var(--ink); background: var(--paper-sink); transform: rotate(90deg) scale(1.05); }
.x:active { transform: rotate(90deg) scale(0.9); }
.card h3 { font-size: 21px; color: var(--ink); margin: 0; }
.sub { font-size: 13px; color: var(--ink-3); margin: 0 0 10px; }
.fields { display: flex; flex-direction: column; gap: 24px; }

.av-row { display: flex; align-items: flex-end; gap: 16px; }
.av-prev {
  position: relative; flex: none; width: 60px; height: 60px; border-radius: 50%;
  background: var(--blue-wash); color: var(--blue);
  display: grid; place-items: center; font-size: 23px; font-weight: 700;
  overflow: hidden; border: 1px solid var(--blue-line); cursor: pointer; padding: 0;
  transition: transform var(--t-fast) var(--ease), box-shadow var(--t-base) var(--ease);
}
.av-prev:hover { transform: scale(1.04); box-shadow: var(--shadow-1); }
.av-prev:active { transform: scale(0.97); }
.av-prev img { width: 100%; height: 100%; object-fit: cover; }
.av-cam {
  position: absolute; inset: 0; display: grid; place-items: center;
  background: rgba(15, 61, 99, 0.55); color: #fff; opacity: 0; transform: translateY(6px);
  transition: opacity var(--t-base) var(--ease), transform var(--t-base) var(--ease);
}
.av-prev:hover .av-cam { opacity: 1; transform: translateY(0); }
.av-load { position: absolute; inset: 0; display: grid; place-items: center; background: rgba(252, 252, 253, 0.72); color: var(--blue); }
.av-prev.busy { cursor: progress; }
.av-prev.busy .av-cam { opacity: 0; }
.av-edit { flex: 1; display: flex; flex-direction: column; gap: 14px; }
.up { align-self: flex-start; padding: 8px 14px; font-size: 13px; }
.up:disabled { opacity: 0.6; cursor: progress; }

.g-field { display: flex; flex-direction: column; gap: 10px; }
.g-lab { font-size: 12px; color: var(--ink-3); font-family: var(--f-mono); letter-spacing: 0.04em; }
.seg { display: inline-flex; align-items: center; gap: 2px; padding: 3px; background: var(--paper-sink); border: 1px solid var(--hair-2); border-radius: 999px; align-self: flex-start; }
.seg-btn {
  border: 0; background: transparent; color: var(--ink-3);
  font-family: var(--f-body); font-size: 13px; font-weight: 600;
  padding: 7px 18px; border-radius: 999px; cursor: pointer;
  transition: color var(--t-base) var(--ease), background var(--t-base) var(--ease),
    box-shadow var(--t-base) var(--ease), transform var(--t-fast) var(--ease);
}
.seg-btn:hover { color: var(--ink); }
.seg-btn.on { background: var(--paper); color: var(--blue); box-shadow: var(--shadow-1); }
.seg-btn:active { transform: scale(0.96); }

.form-err {
  font-size: 13px; color: var(--seal); background: var(--seal-wash);
  border: 1px solid var(--seal-line); border-radius: var(--r-md); padding: 10px 12px; margin: 0;
}
.acts { display: flex; justify-content: flex-end; gap: 12px; margin-top: 2px; }
.submit:disabled { opacity: 0.55; cursor: not-allowed; }
.submit:disabled:hover { transform: none; box-shadow: none; }
.spin { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.pm-enter-active { transition: opacity var(--t-base) var(--ease); }
.pm-leave-active { transition: opacity var(--t-fast) var(--ease-soft); }
.pm-enter-from, .pm-leave-to { opacity: 0; }
.pm-enter-active .card { transition: transform var(--t-slow) var(--ease), opacity var(--t-base) var(--ease); }
.pm-leave-active .card { transition: transform var(--t-fast) var(--ease-soft), opacity var(--t-fast) var(--ease-soft); }
.pm-enter-from .card { transform: translateY(18px) scale(0.96); opacity: 0; }
.pm-leave-to .card { transform: translateY(8px) scale(0.98); opacity: 0; }

.az-enter-active { transition: opacity var(--t-base) var(--ease); }
.az-leave-active { transition: opacity var(--t-fast) var(--ease-soft); }
.az-enter-from, .az-leave-to { opacity: 0; }
</style>
