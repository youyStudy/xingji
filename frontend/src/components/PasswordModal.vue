<script setup>
import { reactive, ref, watch, nextTick } from 'vue'
import { onClickOutside, onKeyStroke } from '@vueuse/core'
import { changePassword } from '../api/xingji'
import FieldInput from './FieldInput.vue'
import { X, Loader2, KeyRound } from 'lucide-vue-next'

// 改密码弹窗：自持表单/校验/提交，成功后通知父级弹 toast。open 由父级控制。
const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['close', 'success'])

const panel = ref(null)
const busy = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const errors = reactive({ oldPassword: '', newPassword: '', confirm: '', form: '' })

function clear() {
  form.oldPassword = form.newPassword = form.confirm = ''
  errors.oldPassword = errors.newPassword = errors.confirm = errors.form = ''
}

function close() {
  if (busy.value) return
  emit('close')
}

const PW_RE = /^(?=.*[A-Za-z])(?=.*\d).{8,32}$/

function validate() {
  errors.oldPassword = !form.oldPassword ? '请输入原密码' : ''
  errors.newPassword = !form.newPassword
    ? '请输入新密码'
    : !PW_RE.test(form.newPassword)
      ? '新密码需 8-32 位，且含字母和数字'
      : form.newPassword === form.oldPassword
        ? '新密码不能与原密码相同'
        : ''
  errors.confirm = !form.confirm
    ? '请再次输入新密码'
    : form.confirm !== form.newPassword
      ? '两次输入的密码不一致'
      : ''
  return !Object.values(errors).some(Boolean)
}

async function submit() {
  errors.form = ''
  if (!validate()) return
  busy.value = true
  try {
    await changePassword(form.oldPassword, form.newPassword)
    emit('success')
    clear()
    emit('close')
  } catch (e) {
    errors.form = e.message || '修改失败'
  } finally {
    busy.value = false
  }
}

// 每次打开重置，锁滚动、聚焦首个输入框
watch(
  () => props.open,
  (open) => {
    if (open) {
      clear()
      document.body.style.overflow = 'hidden'
      nextTick(() => panel.value?.querySelector('input')?.focus())
    } else {
      document.body.style.overflow = ''
    }
  }
)

onClickOutside(panel, () => {
  if (props.open) close()
})
onKeyStroke('Escape', () => {
  if (props.open) close()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="pw">
      <div v-if="open" class="overlay" role="dialog" aria-modal="true" aria-label="修改密码">
        <div class="scrim" @click="close"></div>
        <div class="card" ref="panel">
          <button class="x" aria-label="关闭" @click="close"><X :size="18" /></button>
          <span class="ic"><KeyRound :size="20" /></span>
          <h3 class="serif">修改密码</h3>
          <p class="sub">改完后当前登录仍有效，下次登录用新密码。</p>

          <form class="fields" @submit.prevent="submit" novalidate>
            <FieldInput
              v-model="form.oldPassword"
              label="原密码"
              type="password"
              :error="errors.oldPassword"
              autocomplete="current-password"
            />
            <FieldInput
              v-model="form.newPassword"
              label="新密码"
              type="password"
              :error="errors.newPassword"
              hint="8–32 位，含字母和数字"
              autocomplete="new-password"
            />
            <FieldInput
              v-model="form.confirm"
              label="确认新密码"
              type="password"
              :error="errors.confirm"
              autocomplete="new-password"
            />
            <Transition name="fade-up">
              <p v-if="errors.form" class="form-err">{{ errors.form }}</p>
            </Transition>
            <button class="btn submit" type="submit" :disabled="busy">
              <Loader2 v-if="busy" :size="16" class="spin" />
              <span>{{ busy ? '提交中…' : '确认修改' }}</span>
            </button>
          </form>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  z-index: 95;
  display: grid;
  place-items: center;
  padding: 24px;
}
.scrim {
  position: absolute;
  inset: 0;
  background: rgba(21, 23, 27, 0.42);
  backdrop-filter: blur(2px);
}
.card {
  position: relative;
  z-index: 1;
  width: min(420px, 100%);
  background: var(--paper);
  border: 1px solid var(--hair-2);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-lift);
  padding: 30px 28px 26px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.x {
  position: absolute;
  top: 14px;
  right: 14px;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 0;
  background: none;
  color: var(--ink-3);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: color var(--t-base) var(--ease), background var(--t-base) var(--ease),
    transform var(--t-fast) var(--ease);
}
.x:hover { color: var(--ink); background: var(--paper-sink); transform: rotate(90deg) scale(1.05); }
.x:active { transform: rotate(90deg) scale(0.9); }
.ic {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--blue-wash);
  color: var(--blue);
  display: grid;
  place-items: center;
  margin-bottom: 4px;
}
.card h3 { font-size: 21px; color: var(--ink); margin: 0; }
.sub { font-size: 13px; color: var(--ink-3); margin: 0 0 8px; }
.fields { display: flex; flex-direction: column; gap: 24px; }
.form-err {
  font-size: 13px;
  color: var(--seal);
  background: var(--seal-wash);
  border: 1px solid var(--seal-line);
  border-radius: var(--r-md);
  padding: 10px 12px;
  margin: 0;
}
.submit { justify-content: center; width: 100%; margin-top: 2px; }
.submit:disabled { opacity: 0.7; cursor: progress; }
.spin { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.pw-enter-active { transition: opacity var(--t-base) var(--ease); }
.pw-leave-active { transition: opacity var(--t-fast) var(--ease-soft); }
.pw-enter-from,
.pw-leave-to { opacity: 0; }
.pw-enter-active .card { transition: transform var(--t-slow) var(--ease), opacity var(--t-base) var(--ease); }
.pw-leave-active .card { transition: transform var(--t-fast) var(--ease-soft), opacity var(--t-fast) var(--ease-soft); }
.pw-enter-from .card { transform: translateY(18px) scale(0.96); opacity: 0; }
.pw-leave-to .card { transform: translateY(8px) scale(0.98); opacity: 0; }
</style>
