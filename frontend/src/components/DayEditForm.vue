<script setup>
/**
 * "某一天"信息编辑的小表单（主题/日期），探索页行程面板与我的行程每日规划共用。
 * form 由父级持有（编辑前拷一份现值进来），这里直接 v-model 其字段；
 * dayIndex 由 store 的 saveDay 沿用现值补全，表单不暴露。
 */
defineProps({
  form: { type: Object, required: true }, // { dayId, theme, date }
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
const emit = defineEmits(['save', 'cancel'])
</script>

<template>
  <div class="df" @keydown.esc="emit('cancel')">
    <div class="df-row">
      <input
        class="df-f df-theme"
        v-model="form.theme"
        placeholder="当天主题，如 老城人文+小吃"
        aria-label="当天主题"
        @keydown.enter="emit('save')"
      />
      <input class="df-f" type="date" v-model="form.date" aria-label="当天日期" />
    </div>
    <div class="df-foot">
      <span class="df-err" v-if="error">{{ error }}</span>
      <span v-else></span>
      <span class="df-btns">
        <button class="df-btn ghost" :disabled="busy" @click="emit('cancel')">取消</button>
        <button class="df-btn solid" :disabled="busy" @click="emit('save')">保存</button>
      </span>
    </div>
  </div>
</template>

<style scoped>
.df {
  display: flex; flex-direction: column; gap: 7px;
  padding: 10px; border-radius: var(--r-md);
  background: var(--paper); border: 1px dashed var(--blue-line);
}
.df-row { display: grid; grid-template-columns: 1.6fr 1fr; gap: 6px; }
.df-f {
  font-family: var(--f-body); font-size: 12.5px; color: var(--ink);
  padding: 6px 8px; border: 1px solid var(--hair-2); border-radius: var(--r-sm);
  background: var(--paper-raise); outline: none; min-width: 0;
  transition: border-color var(--t-fast) var(--ease), box-shadow var(--t-fast) var(--ease);
}
.df-f:focus { border-color: var(--blue-line); box-shadow: 0 0 0 2px var(--blue-wash); }
.df-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.df-err { font-size: 11.5px; color: var(--seal); line-height: 1.4; }
.df-btns { display: flex; gap: 6px; flex: none; }
.df-btn { border-radius: var(--r-sm); cursor: pointer; font-family: var(--f-body); font-size: 12px; padding: 5px 12px; transition: all var(--t-fast) var(--ease); }
.df-btn.ghost { border: 1px solid var(--hair-2); background: var(--paper); color: var(--ink-2); }
.df-btn.ghost:hover { border-color: var(--blue-line); color: var(--blue); }
.df-btn.solid { border: 1px solid var(--blue); background: var(--blue); color: #fff; }
.df-btn.solid:hover:not(:disabled) { filter: brightness(1.08); }
.df-btn:disabled { opacity: 0.55; cursor: default; }
</style>
