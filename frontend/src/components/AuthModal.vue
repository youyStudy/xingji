<script setup>
import { computed, reactive, ref, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { onClickOutside, onKeyStroke } from '@vueuse/core'
import { useAuthStore } from '../stores/auth'
import { useUiStore } from '../stores/ui'
import AppBrand from './AppBrand.vue'
import FieldInput from './FieldInput.vue'
import { X, ArrowRight, Loader2 } from 'lucide-vue-next'

const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()

const panel = ref(null)
const isRegister = computed(() => ui.authMode === 'register')

const form = reactive({ phone: '', password: '', nickname: '', email: '' })
const errors = reactive({ phone: '', password: '', nickname: '', email: '', form: '' })

const PHONE_RE = /^1[3-9]\d{9}$/
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const PW_RE = /^(?=.*[A-Za-z])(?=.*\d).{8,32}$/

function validate() {
  errors.phone = !form.phone ? '请输入手机号' : PHONE_RE.test(form.phone) ? '' : '手机号格式不正确'
  if (isRegister.value) {
    errors.password = !form.password
      ? '请输入密码'
      : !PW_RE.test(form.password)
        ? '密码需 8-32 位，且含字母和数字'
        : ''
    errors.nickname = !form.nickname ? '请输入昵称' : form.nickname.length > 20 ? '昵称最多 20 字' : ''
    errors.email = form.email && !EMAIL_RE.test(form.email) ? '邮箱格式不正确' : ''
  } else {
    // 登录不校验强度，历史弱密码账号仍可进入
    errors.password = !form.password ? '请输入密码' : ''
  }
  return !Object.values(errors).some(Boolean)
}

function clearForm() {
  form.phone = ''
  form.password = ''
  form.nickname = ''
  form.email = ''
  errors.phone = errors.password = errors.nickname = errors.email = errors.form = ''
}

function finish() {
  const next = ui.authNext
  ui.closeAuth()
  clearForm()
  // 首页/页脚等入口不指定回跳目标：登录成功后统一进探索页开始规划
  const target = next && next !== '/' ? next : '/explore'
  if (router.currentRoute.value.path !== target) router.replace(target)
}

async function submit() {
  errors.form = ''
  if (!validate()) return
  const ok = isRegister.value
    ? await auth.signUp({ ...form })
    : await auth.signIn({ phone: form.phone, password: form.password })
  if (ok) finish()
  else errors.form = auth.error
}

function switchMode(m) {
  ui.switchMode(m)
  errors.form = ''
}

async function oauth(provider) {
  errors.form = ''
  const ok = await auth.signInWithOauth(provider)
  if (ok) finish()
  else errors.form = auth.error
}

// 弹窗打开时锁滚动、清一次表单级错误，并聚焦手机号；关闭时解锁。
watch(
  () => ui.authOpen,
  (open) => {
    if (open) {
      errors.form = ''
      document.body.style.overflow = 'hidden'
      nextTick(() => panel.value?.querySelector('input')?.focus())
    } else {
      document.body.style.overflow = ''
    }
  }
)

// 交互退出：点遮罩外、按 Esc 都能收起。
onClickOutside(panel, () => {
  if (ui.authOpen && !auth.busy) ui.closeAuth()
})
onKeyStroke('Escape', () => {
  if (ui.authOpen && !auth.busy) ui.closeAuth()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="auth">
      <div v-if="ui.authOpen" class="overlay" role="dialog" aria-modal="true" :aria-label="isRegister ? '注册' : '登录'">
        <div class="scrim" @click="ui.closeAuth()"></div>

        <div class="modal" ref="panel">
          <button class="x" aria-label="关闭" @click="ui.closeAuth()">
            <X :size="18" />
          </button>

          <!-- 左：天空侧栏（与首页 hero 同一套视觉：径向天空 + 太阳 + 云 + 噪点） -->
          <aside class="brand-col">
            <div class="sky-cirrus" aria-hidden="true"></div>
            <div class="sky-noise" aria-hidden="true"></div>
            <svg class="sky-sun" viewBox="-10 -10 220 220" aria-hidden="true">
              <defs>
                <pattern id="authSunHatch" width="17" height="17" patternUnits="userSpaceOnUse" patternTransform="rotate(38)">
                  <line x1="0" y1="0" x2="0" y2="17" stroke="#ffd83a" stroke-width="5" />
                </pattern>
                <radialGradient id="authSunSoft" cx="50%" cy="50%" r="50%">
                  <stop offset="82%" stop-color="#fff" stop-opacity="1" />
                  <stop offset="96%" stop-color="#fff" stop-opacity=".55" />
                  <stop offset="100%" stop-color="#fff" stop-opacity="0" />
                </radialGradient>
                <mask id="authSunMask"><circle cx="100" cy="100" r="100" fill="url(#authSunSoft)" /></mask>
              </defs>
              <g mask="url(#authSunMask)">
                <circle cx="100" cy="100" r="100" fill="url(#authSunHatch)" />
                <circle cx="100" cy="100" r="70" fill="rgba(255,250,226,.55)" />
              </g>
            </svg>
            <div class="bc-inner">
              <div class="brand">
                <AppBrand :size="24" />
                <span class="display">行迹</span>
              </div>
              <Transition name="quote" mode="out-in">
                <p class="quote serif" :key="ui.authMode">
                  {{ isRegister ? '把一句「想出去走走」，落成一份敢照着走的日子。' : '让行程里的每一个地点，都真实可去。' }}
                </p>
              </Transition>
              <p class="fine mono">智能旅游行程规划与管理系统 · 课程设计</p>
            </div>
          </aside>

          <!-- 右：表单 -->
          <section class="body-col">
            <div class="seg" role="tablist">
              <button class="seg-i" :class="{ on: !isRegister }" role="tab" @click="switchMode('login')">登录</button>
              <button class="seg-i" :class="{ on: isRegister }" role="tab" @click="switchMode('register')">注册</button>
              <span class="seg-thumb" :class="{ right: isRegister }"></span>
            </div>

            <h2 class="title serif">
              <Transition name="quote" mode="out-in">
                <span :key="ui.authMode">{{ isRegister ? '开始你的第一段行程' : '欢迎回到行迹' }}</span>
              </Transition>
            </h2>
            <p class="sub">
              {{ isRegister ? '手机号即可注册，之后用同一号码登录。' : '用手机号与密码进入，或用一个第三方账号。' }}
            </p>

            <form class="fields" @submit.prevent="submit" novalidate>
              <Transition name="fade-up">
                <FieldInput
                  v-if="isRegister"
                  key="nick"
                  v-model="form.nickname"
                  label="昵称"
                  :error="errors.nickname"
                  :maxlength="20"
                  autocomplete="nickname"
                />
              </Transition>

              <FieldInput
                v-model="form.phone"
                label="手机号"
                inputmode="numeric"
                :maxlength="11"
                :error="errors.phone"
                autocomplete="tel"
              />
              <FieldInput
                v-model="form.password"
                label="密码"
                type="password"
                :error="errors.password"
                :hint="isRegister ? '8–32 位，含字母和数字' : ''"
                :autocomplete="isRegister ? 'new-password' : 'current-password'"
              />
              <Transition name="fade-up">
                <FieldInput
                  v-if="isRegister"
                  key="email"
                  v-model="form.email"
                  label="邮箱（选填）"
                  type="email"
                  :error="errors.email"
                  autocomplete="email"
                />
              </Transition>

              <Transition name="fade-up">
                <p v-if="errors.form" class="form-err">{{ errors.form }}</p>
              </Transition>

              <button class="btn submit" type="submit" :disabled="auth.busy">
                <Loader2 v-if="auth.busy" :size="17" class="spin" />
                <span>{{ auth.busy ? '请稍候…' : (isRegister ? '注册并进入' : '登录') }}</span>
                <ArrowRight v-if="!auth.busy" :size="17" />
              </button>
            </form>

            <div class="divider"><span>或</span></div>
            <div class="oauth">
              <button class="btn btn-ghost ob" :disabled="auth.busy" @click="oauth('qq')">
                <span class="ob-dot qq">Q</span> QQ 登录
              </button>
              <button class="btn btn-ghost ob" :disabled="auth.busy" @click="oauth('wechat')">
                <span class="ob-dot wx">微</span> 微信登录
              </button>
            </div>
            <p class="oauth-note">第三方登录当前为演示态（mock），走通链路用</p>

            <p class="switch">
              {{ isRegister ? '已经有账号了？' : '还没有账号？' }}
              <button class="lk" @click="switchMode(isRegister ? 'login' : 'register')">
                {{ isRegister ? '去登录' : '去注册' }}
              </button>
            </p>
          </section>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* ════ 遮罩：夜空压暗 + 轻微模糊 ════ */
.overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: clamp(16px, 4vw, 40px);
}
.scrim {
  position: absolute;
  inset: 0;
  background: rgba(6, 6, 6, 0.45);
  backdrop-filter: blur(3px);
}

/* ════ 面板：玻璃胶囊（与首页导航 pill 同配方）════ */
.modal {
  position: relative;
  z-index: 1;
  width: min(880px, 100%);
  max-height: calc(100vh - 40px);
  overflow: auto;
  border-radius: 34px;                       /* 参考站 .t-card 玻璃卡圆角 */
  background: var(--bg-base, #fff9f1);
  border: 1px solid rgba(255, 249, 233, 0.55);
  box-shadow: 4px 4px 20px 0 rgba(0, 0, 0, 0.3);
  display: grid;
  grid-template-columns: 0.92fr 1.08fr;
  overflow: hidden;
}

/* 关闭钮：首页 34px 圆盘控件 */
.x {
  position: absolute;
  top: 16px;
  right: 16px;
  z-index: 3;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 1px solid var(--toggle-border, #e7dfcd);
  background: var(--toggle-track, #fff);
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.1);
  color: var(--ink, #060606);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: transform 0.2s ease, background-color 0.35s ease;
}
.x:hover { transform: scale(1.06); }
.x:active { transform: scale(0.94); }

/* ════ 左：天空侧栏（首页 hero 同款）════ */
.brand-col {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  background: radial-gradient(ellipse 120% 150% at 68.65% 23.5%,
    var(--hero-from, #71b7f4) 0%, var(--hero-to, #4a86b8) 100%);
  color: var(--cream-2, #fff9e9);
}
/* 云絮：screen 混合的极淡高光（首页 .nz-hero-cirrus 同参） */
.sky-cirrus {
  position: absolute;
  inset: 0 -10%;
  opacity: 0.6;
  mix-blend-mode: screen;
  pointer-events: none;
  background-image:
    radial-gradient(38% 22% at 22% 34%, rgba(255, 255, 255, 0.85) 0%, rgba(255, 255, 255, 0) 70%),
    radial-gradient(30% 18% at 62% 24%, rgba(255, 255, 255, 0.7) 0%, rgba(255, 255, 255, 0) 72%),
    radial-gradient(44% 26% at 78% 46%, rgba(255, 255, 255, 0.6) 0%, rgba(255, 255, 255, 0) 74%);
}
/* 噪点：feTurbulence 平铺，opacity .1（首页 .nz-hero-noise 同参） */
.sky-noise {
  position: absolute;
  inset: 0;
  opacity: 0.1;
  pointer-events: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='256' height='256'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.8' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='256' height='256' filter='url(%23n)'/%3E%3C/svg%3E");
  background-size: 1024px 1024px;
}
/* 太阳：斜线排线盘，右上角出画一点 */
.sky-sun {
  position: absolute;
  top: -34px;
  right: -46px;
  width: 210px;
  height: 210px;
  pointer-events: none;
  animation: auth-sun-breathe 9s ease-in-out infinite alternate;
}
@keyframes auth-sun-breathe {
  from { transform: scale(0.96); opacity: 0.9; }
  to   { transform: scale(1.05); opacity: 1; }
}

.bc-inner {
  position: relative;
  z-index: 2;
  padding: clamp(28px, 3.2vw, 44px);
  display: flex;
  flex-direction: column;
  gap: 22px;
  width: 100%;
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  color: #fff9e9;
  font-family: var(--f-serif, 'DM Serif Display', 'Songti SC', serif);
  font-size: 22px;
  letter-spacing: 1px;
}
.route { width: 200px; height: 40px; opacity: 0.9; }
/* 引言：超大衬线（首页 hero 标题同族），字距随字号收紧 */
.quote {
  font-family: var(--f-serif, 'DM Serif Display', 'Songti SC', serif);
  font-size: clamp(24px, 2.4vw, 33px);
  line-height: 1.28;
  letter-spacing: 1px;
  color: #fff9e9;
  margin: 0;
  max-width: 13ch;
  min-height: 3.6em;
}
/* 小标签：JetBrains Mono 大写（首页 eyebrow 同款） */
.fine {
  font-family: var(--f-mono, 'JetBrains Mono', monospace);
  font-size: 11px;
  letter-spacing: 1.8px;
  text-transform: uppercase;
  color: rgba(255, 249, 233, 0.72);
}

/* ════ 右：表单 ════ */
.body-col {
  padding: clamp(30px, 3.4vw, 46px);
  display: flex;
  flex-direction: column;
  background: var(--bg-base, #fff9f1);
  color: var(--text-heading, #060606);
}

/* 登录/注册开关：白色胶囊 + 滑动块（首页 nav 胶囊同语言） */
.seg {
  position: relative;
  display: inline-grid;
  grid-template-columns: 1fr 1fr;
  background: rgba(255, 255, 255, 0.55);
  border: 1px solid var(--toggle-border, #e7dfcd);
  border-radius: 999px;
  padding: 4px;
  width: 214px;
  margin-bottom: 26px;
}
.seg-i {
  position: relative;
  z-index: 1;
  border: 0;
  background: none;
  cursor: pointer;
  font-family: var(--f-sans, 'Space Grotesk', sans-serif);
  font-size: 15px;
  color: var(--text-eyebrow, rgba(0, 0, 0, 0.5));
  padding: 9px 0;
  border-radius: 999px;
  transition: color 0.25s ease;
}
.seg-i.on { color: var(--text-heading, #060606); }
.seg-thumb {
  position: absolute;
  z-index: 0;
  top: 4px;
  left: 4px;
  width: calc(50% - 4px);
  height: calc(100% - 8px);
  background: #fff;
  border-radius: 999px;
  box-shadow: 0 4px 12px -4px rgba(30, 58, 95, 0.28);
  transition: transform 0.45s cubic-bezier(0.4, 0, 0.2, 1);
}
.seg-thumb.right { transform: translateX(100%); }

/* 标题：大号衬线（首页标题同族） */
.title {
  font-family: var(--f-serif, 'DM Serif Display', 'Songti SC', serif);
  font-size: clamp(26px, 2.9vw, 36px);
  line-height: 1.2;
  letter-spacing: 1.2px;
  color: var(--text-heading, #060606);
  margin: 0;
}
.sub {
  font-family: var(--f-sans, 'Space Grotesk', sans-serif);
  font-size: 15px;
  color: var(--text-eyebrow, rgba(0, 0, 0, 0.5));
  margin: 10px 0 26px;
  line-height: 1.6;
}
.fields { display: flex; flex-direction: column; gap: 24px; }
.form-err {
  font-family: var(--f-sans, 'Space Grotesk', sans-serif);
  font-size: 13px;
  color: var(--seal, #b1342a);
  background: var(--seal-wash, #f7e8e6);
  border: 1px solid var(--seal-line, rgba(177, 52, 42, 0.28));
  border-radius: 12px;
  padding: 10px 12px;
  margin: 0;
}

/* 主按钮：金色胶囊（首页唯一强调色），hover 回弹 */
.submit {
  justify-content: center;
  margin-top: 2px;
  width: 100%;
  padding: 15px 24px;
  border: 0;
  border-radius: 9999px;
  background: var(--gold, #ffda3f);
  color: #060606;
  font-family: var(--f-sans, 'Space Grotesk', sans-serif);
  font-weight: 600;
  font-size: 15px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  transition: transform 0.18s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.2s ease;
}
.submit:hover { transform: scale(1.02); box-shadow: 0 4px 12px -4px rgba(30, 58, 95, 0.4); }
.submit:active { transform: scale(0.98); }
.submit:disabled { opacity: 0.7; cursor: progress; }
.spin { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.divider {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 24px 0 14px;
  color: var(--text-eyebrow, rgba(0, 0, 0, 0.4));
  font-family: var(--f-mono, 'JetBrains Mono', monospace);
  font-size: 11px;
  letter-spacing: 1.4px;
  text-transform: uppercase;
}
.divider::before,
.divider::after { content: ""; height: 1px; flex: 1; background: rgba(0, 0, 0, 0.1); }

/* 第三方登录：白底细描边胶囊 */
.oauth { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.ob {
  justify-content: center;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-radius: 9999px;
  border: 1px solid var(--toggle-border, #e7dfcd);
  background: #fff;
  color: #060606;
  font-family: var(--f-sans, 'Space Grotesk', sans-serif);
  font-size: 14px;
  cursor: pointer;
  transition: transform 0.18s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.2s ease;
}
.ob:hover { transform: scale(1.03); box-shadow: 0 4px 12px -4px rgba(30, 58, 95, 0.3); }
.ob-dot {
  width: 20px; height: 20px; border-radius: 50%;
  display: grid; place-items: center;
  font-size: 11px; font-weight: 700; color: #fff;
}
.ob-dot.qq { background: #12b7f5; }
.ob-dot.wx { background: #07c160; }
.oauth-note {
  font-family: var(--f-mono, 'JetBrains Mono', monospace);
  font-size: 11px;
  letter-spacing: 0.6px;
  color: var(--text-eyebrow, rgba(0, 0, 0, 0.4));
  margin: 10px 0 0;
}

.switch {
  margin-top: 22px;
  font-family: var(--f-sans, 'Space Grotesk', sans-serif);
  font-size: 14px;
  color: var(--text-eyebrow, rgba(0, 0, 0, 0.5));
  text-align: center;
}
.lk {
  border: 0; background: none; cursor: pointer;
  color: var(--ink, #060606);
  font-weight: 600;
  font-family: var(--f-sans, 'Space Grotesk', sans-serif);
  font-size: 14px;
  border-bottom: 1px solid var(--gold, #ffda3f);
  padding-bottom: 1px;
  transition: opacity 0.25s ease;
}
.lk:hover { opacity: 0.7; }

/* 弹窗入场：遮罩淡入 + 面板自下升起（曲线与首页回弹曲线一致） */
.auth-enter-active { transition: opacity 0.35s ease; }
.auth-leave-active { transition: opacity 0.18s ease; }
.auth-enter-from,
.auth-leave-to { opacity: 0; }
.auth-enter-active .modal { transition: transform 0.62s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.35s ease; }
.auth-leave-active .modal { transition: transform 0.18s ease, opacity 0.18s ease; }
.auth-enter-from .modal { transform: translateY(28px) scale(0.97); opacity: 0; }
.auth-leave-to .modal { transform: translateY(12px) scale(0.985); opacity: 0; }

.quote-enter-active,
.quote-leave-active { transition: opacity 0.35s ease, transform 0.35s cubic-bezier(0.22, 1, 0.36, 1); }
.quote-enter-from { opacity: 0; transform: translateY(10px); }
.quote-leave-to { opacity: 0; transform: translateY(-10px); }

@media (max-width: 720px) {
  .modal { grid-template-columns: 1fr; }
  .brand-col { display: none; }
  .x { top: 10px; right: 10px; }
}
</style>
