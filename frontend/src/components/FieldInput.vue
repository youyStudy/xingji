<script setup>
import { computed, ref, useSlots } from 'vue'
import { Eye, EyeOff, Check } from 'lucide-vue-next'

// 下划线输入框：标签浮起、下划线随焦点从中心抽出、错误态转红并滑出提示。
// 密码框自带显隐切换。纯受控组件，校验逻辑交给父级。
const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  label: { type: String, default: '' },
  type: { type: String, default: 'text' },
  error: { type: String, default: '' },
  hint: { type: String, default: '' },
  autocomplete: { type: String, default: 'off' },
  maxlength: { type: [String, Number], default: undefined },
  readonly: { type: Boolean, default: false },
  inputmode: { type: String, default: undefined }
})
const emit = defineEmits(['update:modelValue', 'blur'])

const focused = ref(false)
const revealPw = ref(false)
const isPw = computed(() => props.type === 'password')
const inputType = computed(() => (isPw.value ? (revealPw.value ? 'text' : 'password') : props.type))
const filled = computed(() => String(props.modelValue ?? '').length > 0)
const ok = computed(() => !props.error && filled.value && !focused.value)

const slots = useSlots()

function onInput(e) {
  emit('update:modelValue', e.target.value)
}
</script>

<template>
  <div class="field" :class="{ focused, 'has-error': !!error, 'has-value': filled }">
    <div class="box">
      <label class="lab">{{ label }}</label>
      <input
        :type="inputType"
        :value="modelValue"
        :autocomplete="autocomplete"
        :maxlength="maxlength"
        :readonly="readonly"
        :inputmode="inputmode"
        @input="onInput"
        @focus="focused = true"
        @blur="focused = false; emit('blur')"
      />
      <button
        v-if="isPw"
        type="button"
        class="toggle"
        :aria-label="revealPw ? '隐藏密码' : '显示密码'"
        @click="revealPw = !revealPw"
        tabindex="-1"
      >
        <EyeOff v-if="revealPw" :size="16" />
        <Eye v-else :size="16" />
      </button>
      <transition name="pop">
        <span v-if="ok" class="ok"><Check :size="14" /></span>
      </transition>
      <span class="underline" aria-hidden="true"></span>
    </div>

    <transition name="slide">
      <p v-if="error" class="msg err">{{ error }}</p>
      <p v-else-if="hint" class="msg hint">{{ hint }}</p>
    </transition>
  </div>
</template>

<style scoped>
.field {
  position: relative;
  padding-top: 20px;
}
.box {
  position: relative;
  display: flex;
  align-items: center;
}
.lab {
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  font-size: 15px;
  color: var(--ink-3);
  pointer-events: none;
  transform-origin: left center;
  transition: transform var(--t-base) var(--ease), color var(--t-base) var(--ease);
}
.field.focused .lab,
.field.has-value .lab {
  transform: translateY(-50%) translateY(-23px) scale(0.82);
  color: var(--ink-3);
}
.field.focused .lab {
  color: var(--blue);
}
input {
  width: 100%;
  border: 0;
  background: none;
  outline: none;
  font-family: var(--f-body);
  font-size: 16px;
  color: var(--ink);
  padding: 6px 30px 8px 0;
}
input:read-only {
  color: var(--ink-3);
}
.underline {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 1.5px;
  background: var(--hair);
  border-radius: 2px;
}
.underline::after {
  content: "";
  position: absolute;
  left: 50%;
  right: 50%;
  bottom: 0;
  height: 1.5px;
  background: var(--blue);
  transition: left var(--t-base) var(--ease), right var(--t-base) var(--ease);
}
.field.focused .underline::after {
  left: 0;
  right: 0;
}
.field.has-error .underline { background: var(--seal-line); }
.field.has-error .underline::after { background: var(--seal); left: 0; right: 0; }
.field.has-error .lab { color: var(--seal); }

.toggle {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  border: 0;
  background: none;
  color: var(--ink-3);
  cursor: pointer;
  padding: 6px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  transition: color var(--t-base) var(--ease), transform var(--t-fast) var(--ease);
}
.toggle:hover { color: var(--blue); transform: translateY(-50%) scale(1.12); }
.toggle:active { transform: translateY(-50%) scale(0.92); }

.ok {
  position: absolute;
  right: 2px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--blue);
  display: grid;
  place-items: center;
}

.msg {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.5;
}
.msg.err { color: var(--seal); }
.msg.hint { color: var(--ink-4); }

/* 过渡 */
.pop-enter-active { transition: transform var(--t-base) var(--ease), opacity var(--t-base) var(--ease); }
.pop-enter-from { opacity: 0; transform: translateY(-50%) scale(0.4); }
.slide-enter-active,
.slide-leave-active {
  transition: opacity var(--t-base) var(--ease), transform var(--t-base) var(--ease);
}
.slide-enter-from,
.slide-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
.slide-leave-active { position: absolute; }
</style>
