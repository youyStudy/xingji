<script setup>
/**
 * 节点编辑/新增的内联小表单（行程面板用）。
 * form 由父级持有（编辑既有节点时先拷贝一份现值进来），这里直接 v-model 其字段；
 * 保存校验（空名/时间倒挂）与提交都在 explore store 的 saveNode 里做。
 */
defineProps({
  form: { type: Object, required: true },
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
const emit = defineEmits(['save', 'cancel'])
const TYPES = ['景点', '餐饮', '交通', '住宿']
</script>

<template>
  <div class="nf" @keydown.esc="emit('cancel')">
    <input
      class="nf-f nf-name"
      v-model="form.placeName"
      placeholder="地点名，如 洪崖洞"
      aria-label="地点名"
      @keydown.enter="emit('save')"
    />
    <div class="nf-row">
      <select class="nf-f" v-model="form.nodeType" aria-label="类型">
        <option v-for="t in TYPES" :key="t" :value="t">{{ t }}</option>
      </select>
      <input class="nf-f" type="time" v-model="form.startTime" aria-label="开始时间" />
      <input class="nf-f" type="time" v-model="form.endTime" aria-label="结束时间" />
      <input class="nf-f nf-cost" type="number" min="0" step="1" v-model.number="form.cost" placeholder="花费" aria-label="花费" />
    </div>
    <div class="nf-foot">
      <span class="nf-err" v-if="error">{{ error }}</span>
      <span v-else></span>
      <span class="nf-btns">
        <button class="nf-btn ghost" :disabled="busy" @click="emit('cancel')">取消</button>
        <button class="nf-btn solid" :disabled="busy" @click="emit('save')">保存</button>
      </span>
    </div>
  </div>
</template>

<style scoped>
.nf {
  display: flex; flex-direction: column; gap: 7px;
  padding: 10px; border-radius: var(--r-md);
  background: var(--paper); border: 1px dashed var(--blue-line);
}
.nf-f {
  font-family: var(--f-body); font-size: 12.5px; color: var(--ink);
  padding: 6px 8px; border: 1px solid var(--hair-2); border-radius: var(--r-sm);
  background: var(--paper-raise); outline: none; min-width: 0;
  transition: border-color var(--t-fast) var(--ease), box-shadow var(--t-fast) var(--ease);
}
.nf-f:focus { border-color: var(--blue-line); box-shadow: 0 0 0 2px var(--blue-wash); }
.nf-name { flex: 1; }
.nf-row { display: grid; grid-template-columns: 1fr 1fr 1fr 0.8fr; gap: 6px; }
.nf-cost::placeholder { color: var(--ink-4); }
.nf-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.nf-err { font-size: 11.5px; color: var(--seal); line-height: 1.4; }
.nf-btns { display: flex; gap: 6px; flex: none; }
.nf-btn {
  border-radius: var(--r-sm); cursor: pointer; font-family: var(--f-body); font-size: 12px;
  padding: 5px 12px; transition: all var(--t-fast) var(--ease);
}
.nf-btn.ghost { border: 1px solid var(--hair-2); background: var(--paper); color: var(--ink-2); }
.nf-btn.ghost:hover { border-color: var(--blue-line); color: var(--blue); }
.nf-btn.solid { border: 1px solid var(--blue); background: var(--blue); color: #fff; }
.nf-btn.solid:hover:not(:disabled) { filter: brightness(1.08); }
.nf-btn:disabled { opacity: 0.55; cursor: default; }
</style>
