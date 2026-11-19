<script setup>
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'

/**
 * 确认弹窗（替代 window.confirm）——白卡 + 背景模糊 + 药丸按钮，危险操作确认的统一入口。
 * open 由父级控制；这里只 emit，真正执行/关闭都由父级做（异步跑完再关，失败留在弹窗上可重试）。
 * 打开时焦点落在「取消」上：危险操作的默认回车是“不做”，更安全。
 */
const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '确认执行该操作？' },
  description: { type: String, default: '' },
  confirmText: { type: String, default: '确认' },
  cancelText: { type: String, default: '取消' },
  danger: { type: Boolean, default: false },
  busy: { type: Boolean, default: false }
})
const emit = defineEmits(['confirm', 'cancel'])
const cancelBtn = ref(null)

function cancel() {
  if (!props.busy) emit('cancel')
}
function onKey(e) {
  if (e.key === 'Escape') cancel()
}
watch(
  () => props.open,
  (v) => {
    if (v) {
      window.addEventListener('keydown', onKey)
      nextTick(() => cancelBtn.value?.focus())
    } else {
      window.removeEventListener('keydown', onKey)
    }
  }
)
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <!-- duration 显式声明,不依赖 transitionend(后台标签页动画节流时事件可能不触发,元素会卡在 leave 态) -->
    <transition name="cm" :duration="{ enter: 400, leave: 240 }">
      <div v-if="open" class="cm-mask" @mousedown.self="cancel">
        <div class="cm-card" role="alertdialog" aria-modal="true" :aria-label="title">
          <h3 class="cm-title">{{ title }}</h3>
          <p v-if="description" class="cm-desc">{{ description }}</p>
          <div class="cm-acts">
            <button ref="cancelBtn" class="cm-btn ghost" :disabled="busy" @click="cancel">{{ cancelText }}</button>
            <button class="cm-btn solid" :class="{ danger }" :disabled="busy" @click="emit('confirm')">{{ confirmText }}</button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.cm-mask {
  position: fixed; inset: 0; z-index: 1400;
  display: grid; place-items: center; padding: 24px;
  background: rgba(21, 23, 27, 0.28);
  backdrop-filter: blur(7px);
  -webkit-backdrop-filter: blur(7px);
}
.cm-card {
  width: min(420px, 100%);
  background: var(--paper);
  border: 1px solid var(--hair-2);
  border-radius: var(--r-lg);
  padding: 26px 28px 24px;
  box-shadow: var(--shadow-2);
}
.cm-title { margin: 0 0 10px; font-size: 16.5px; font-weight: 700; color: var(--ink); letter-spacing: 0.01em; }
.cm-desc { margin: 0; font-size: 13.5px; line-height: 1.7; color: var(--ink-2); white-space: pre-wrap; }

.cm-acts { display: flex; justify-content: flex-end; gap: 10px; margin-top: 26px; }
.cm-btn {
  border-radius: 999px; padding: 9px 22px;
  font-family: var(--f-body); font-size: 13.5px; font-weight: 600; letter-spacing: 0.02em;
  cursor: pointer;
  transition: transform var(--t-fast) var(--ease), box-shadow var(--t-base) var(--ease),
    background var(--t-fast) var(--ease), color var(--t-fast) var(--ease), border-color var(--t-fast) var(--ease);
}
.cm-btn.ghost { border: 1px solid var(--hair); background: var(--paper); color: var(--ink-2); }
.cm-btn.ghost:hover:not(:disabled) { border-color: var(--ink-3); color: var(--ink); transform: scale(1.04); }
.cm-btn.ghost:focus-visible { outline: none; box-shadow: 0 0 0 3px rgba(21, 23, 27, 0.14); }
.cm-btn.solid { border: 1px solid var(--ink); background: var(--ink); color: #fff; }
.cm-btn.solid.danger { border-color: var(--seal); background: var(--seal); }
.cm-btn.solid:hover:not(:disabled) { transform: scale(1.04); box-shadow: var(--shadow-2); filter: brightness(1.06); }
.cm-btn.solid:focus-visible { outline: none; box-shadow: 0 0 0 3px rgba(177, 52, 42, 0.22); }
.cm-btn:active:not(:disabled) { transform: scale(0.94); box-shadow: none; }
.cm-btn:disabled { opacity: 0.55; cursor: default; }

/* 弹窗形变：背景淡入 + 卡片轻微缩放上浮（CM = Confirm Morph） */
.cm-enter-active { transition: opacity var(--t-base) var(--ease); }
.cm-leave-active { transition: opacity var(--t-fast) var(--ease); pointer-events: none; }
.cm-enter-from, .cm-leave-to { opacity: 0; }
.cm-enter-active .cm-card { transition: transform var(--t-morph) var(--ease); }
.cm-leave-active .cm-card { transition: transform var(--t-fast) var(--ease); }
.cm-enter-from .cm-card { transform: scale(0.92) translateY(10px); }
.cm-leave-to .cm-card { transform: scale(0.96); }
</style>
