<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import api from '../api'
import { ElMessage } from 'element-plus'
import bus from '../utils/bus'

// 排产视图：建议顺序与未排入区都由后端按最新数据计算（跨烤箱容量 / 逾期风险 / 版本一致性），
// 前端只提交“优先级顺序”，绝不在本地自行排序结果或只保存一串编号。
const view = ref(null)
const priorities = ref([])       // 当前编辑中的优先级（工单 id 有序列表）
const savedPriorities = ref([])  // 最近一次加载/保存时的优先级（用于判断是否有未保存调整）
const loading = ref(false)
const saving = ref(false)
const saveDialog = ref(false)
const saveForm = reactive({ name: '', operator: '' })

const scheduledById = computed(() =>
  Object.fromEntries((view.value?.scheduled || []).map((s) => [s.orderId, s])))
const unscheduledById = computed(() =>
  Object.fromEntries((view.value?.unscheduled || []).map((u) => [u.orderId, u])))

// 两个区都按 priorities 顺序渲染：排入区在上，未排入区在下
const scheduledRows = computed(() => priorities.value.map((id) => scheduledById.value[id]).filter(Boolean))
const unscheduledRows = computed(() => priorities.value.map((id) => unscheduledById.value[id]).filter(Boolean))

const dirty = computed(() => priorities.value.join(',') !== savedPriorities.value.join(','))
const stale = computed(() => !!view.value?.stale)
// 还没有任何版本时允许直接保存默认建议；已有版本则需有调整或版本已过期
const canSave = computed(() =>
  priorities.value.length > 0 && (!view.value?.versionId || dirty.value || stale.value))

function apply(d, resetSaved) {
  if (resetSaved) previewSeq += 1 // 使仍在途的旧试算响应失效
  view.value = d
  priorities.value = [...(d.priorities || [])]
  if (resetSaved) savedPriorities.value = [...(d.priorities || [])]
}

async function load() {
  loading.value = true
  try {
    apply(await api.scheduleCurrent(), true)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

// 重算：基于最新数据。有未保存调整时保留当前优先级试算，否则整体刷新到最新版本
async function recalc() {
  loading.value = true
  try {
    if (dirty.value) apply(await api.schedulePreview(priorities.value), false)
    else apply(await api.scheduleCurrent(), true)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

let previewTimer = null
let previewSeq = 0
function queuePreview() {
  clearTimeout(previewTimer)
  const seq = ++previewSeq
  previewTimer = setTimeout(async () => {
    try {
      const d = await api.schedulePreview(priorities.value)
      // 较慢的旧响应不覆盖更新状态（拖动/保存后可能有过期响应才返回）
      if (seq === previewSeq) apply(d, false)
    } catch (e) {
      ElMessage.error(e.message)
    }
  }, 250)
}

// ---------------- 拖拽（原生 HTML5 DnD） ----------------
// 只有「待烤」工单出现在排产列表中，已开始/已完成的工单不在列表即不可拖动
const drag = ref(null) // { zone: 'scheduled' | 'unscheduled', index }

function onDragStart(zone, index, ev) {
  drag.value = { zone, index }
  ev.dataTransfer.effectAllowed = 'move'
}
function onDragEnd() {
  drag.value = null
}

// 用新的“已排入子序列”重建整体优先级：未排入的 id 保持相对位置不变
function rebuildPriorities(newScheduledIds) {
  const inScheduled = new Set(newScheduledIds)
  const it = newScheduledIds[Symbol.iterator]()
  priorities.value = priorities.value.map((id) => (inScheduled.has(id) ? it.next().value : id))
}

function applyScheduledIds(scheduledIds) {
  rebuildPriorities(scheduledIds)
  queuePreview()
}

// 拖到已排入区第 dropIndex 个位置之前
function onDropScheduled(dropIndex, ev) {
  ev.preventDefault()
  if (!drag.value) return
  const { zone, index } = drag.value
  drag.value = null
  const ids = scheduledRows.value.map((r) => r.orderId)
  if (zone === 'scheduled') {
    const [srcId] = ids.splice(index, 1)
    ids.splice(index < dropIndex ? dropIndex - 1 : dropIndex, 0, srcId)
  } else {
    ids.splice(dropIndex, 0, unscheduledRows.value[index].orderId)
  }
  applyScheduledIds(ids)
}

// 拖到已排入区末尾（列表空白处）
function onDropScheduledEnd(ev) {
  ev.preventDefault()
  if (!drag.value) return
  const { zone, index } = drag.value
  drag.value = null
  const ids = scheduledRows.value.map((r) => r.orderId)
  if (zone === 'scheduled') {
    const [srcId] = ids.splice(index, 1)
    ids.push(srcId)
  } else {
    ids.push(unscheduledRows.value[index].orderId)
  }
  applyScheduledIds(ids)
}

// 上移/下移按钮（与拖拽等价的精确调整）
function moveScheduled(index, dir) {
  const ids = scheduledRows.value.map((r) => r.orderId)
  const j = index + dir
  if (j < 0 || j >= ids.length) return
  const [id] = ids.splice(index, 1)
  ids.splice(j, 0, id)
  applyScheduledIds(ids)
}

// 未排入工单提到队首：预计逾期的提前后可能排入；容量不足/无可用烤箱的仍会留在未排入区
function moveToFront(orderId) {
  priorities.value = [orderId, ...priorities.value.filter((id) => id !== orderId)]
  queuePreview()
}

// ---------------- 保存版本 ----------------
function openSave() {
  saveForm.name = ''
  saveDialog.value = true
}

async function save() {
  saving.value = true
  try {
    const d = await api.saveScheduleVersion({
      name: saveForm.name,
      operator: saveForm.operator,
      baseVersionId: view.value ? view.value.versionId : null,
      fingerprint: view.value ? view.value.fingerprint : null,
      priorities: priorities.value
    })
    saveDialog.value = false
    apply(d, true)
    ElMessage.success('已保存排产版本 #' + d.versionId)
    emit('changed')
  } catch (e) {
    // 并发保存冲突 / 数据已变化：提示后刷新，看到最新顺序与冲突原因
    saveDialog.value = false
    ElMessage.error(e.message)
    await load()
  } finally {
    saving.value = false
  }
}

const emit = defineEmits(['changed'])

// 故障接管后烤箱归属变化 → 排产输入已变，重载视图
const offTaken = bus.on('takeover:taken', () => load())
onMounted(load)
onUnmounted(() => {
  offTaken()
  clearTimeout(previewTimer)
})

// ---------------- 展示辅助 ----------------
const fmt = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '-')
const riskType = (r) => (r === '正常' ? 'success' : r === '紧张' ? 'warning' : 'info')
const reasonLabel = (t) =>
  ({ 'no-oven': '没有可用烤箱', capacity: '容量不足', overdue: '预计逾期', recipe: '配方缺失' }[t] || '未排入')
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <h2>生产排产 · 建议顺序</h2>
      <div class="bar-acts">
        <el-button @click="recalc" :loading="loading">重算</el-button>
        <el-button type="primary" :disabled="!canSave" @click="openSave">保存排产版本</el-button>
      </div>
    </div>

    <!-- 版本状态条：过期版本明确标注，展示的是基于最新数据的重算结果 -->
    <el-alert
      v-if="view && view.versionId"
      :type="stale ? 'warning' : 'success'"
      show-icon :closable="false" class="ver-alert"
    >
      <template #title>
        <span>
          当前版本 <b>#{{ view.versionId }} · {{ view.versionName }}</b>
          （{{ view.savedBy }} 保存于 {{ fmt(view.savedAt) }}）
          <template v-if="stale">
            —— <b>已过期</b>：烤箱状态 / 工单烤盘数 / 交付截止时间已变化，
            以下为基于最新数据的重算结果，确认后可保存为新版本
          </template>
          <template v-else>—— 与当前生产数据一致</template>
        </span>
      </template>
    </el-alert>
    <el-alert
      v-else-if="view"
      type="info" show-icon :closable="false" class="ver-alert"
      title="暂无已保存的排产版本：当前为默认建议（按交付截止时间升序）。拖动调整优先级后可保存为版本。"
    />

    <!-- 建议顺序（已排入） -->
    <div class="zone">
      <div class="zone-head">
        <span>建议顺序 · 已排入 {{ scheduledRows.length }} 张</span>
        <span class="zone-hint">拖动调整优先级；已开始 / 已完成的工单不参与排产，不可拖动</span>
      </div>
      <div class="rows" @dragover.prevent @drop="onDropScheduledEnd">
        <div
          v-for="(row, i) in scheduledRows" :key="row.orderId"
          class="row" draggable="true"
          @dragstart="onDragStart('scheduled', i, $event)"
          @dragend="onDragEnd"
          @dragover.prevent
          @drop.stop="onDropScheduled(i, $event)"
        >
          <span class="seq">{{ i + 1 }}</span>
          <div class="cell main">
            <div class="line1">
              <span class="badge">#{{ row.orderId }}</span>
              <b>{{ row.recipeName }}</b>
              <span class="muted">烤盘 {{ row.trays }} / 容量 {{ row.ovenCapacity }}</span>
              <el-tag size="small" type="success">容量正常</el-tag>
            </div>
            <div class="line2 muted">{{ row.note }}</div>
          </div>
          <div class="cell oven">
            <div>建议 <b class="oven-code">{{ row.ovenCode }}</b></div>
            <div class="muted small">
              归属 {{ row.ownOvenCode }}
              <el-tag v-if="row.ownOvenStatus === '故障'" size="small" type="danger" effect="dark">炉故障</el-tag>
              <template v-else-if="row.ownOvenId !== row.ovenId">（建议改挂，实际归属不变）</template>
            </div>
          </div>
          <div class="cell time">
            <div>预计 {{ fmt(row.estStart) }} → {{ fmt(row.estFinish) }}</div>
            <div class="muted small">交付截止 {{ fmt(row.deadline) }}</div>
          </div>
          <div class="cell risk">
            <el-tag :type="riskType(row.risk)">{{ row.risk }}</el-tag>
          </div>
          <div class="cell acts">
            <el-button size="small" link :disabled="i === 0" @click="moveScheduled(i, -1)">上移</el-button>
            <el-button size="small" link :disabled="i === scheduledRows.length - 1" @click="moveScheduled(i, 1)">下移</el-button>
          </div>
        </div>
        <div class="empty" v-if="!scheduledRows.length">暂无可排入的工单</div>
      </div>
    </div>

    <!-- 未排入区：容量预警 / 预计逾期等原因如实列出，不伪装成已安排 -->
    <div class="zone zone-unscheduled">
      <div class="zone-head">
        <span>未排入区 · {{ unscheduledRows.length }} 张</span>
        <span class="zone-hint">未排入原因如实列出；可拖入上方或提到队首尝试优先排产</span>
      </div>
      <div class="rows">
        <div
          v-for="(row, i) in unscheduledRows" :key="row.orderId"
          class="row row-unscheduled" draggable="true"
          @dragstart="onDragStart('unscheduled', i, $event)"
          @dragend="onDragEnd"
        >
          <span class="seq">-</span>
          <div class="cell main">
            <div class="line1">
              <span class="badge">#{{ row.orderId }}</span>
              <b>{{ row.recipeName }}</b>
              <span class="muted">烤盘 {{ row.trays }}</span>
              <el-tag size="small" :type="row.reasonType === 'overdue' ? 'danger' : 'warning'">
                {{ reasonLabel(row.reasonType) }}
              </el-tag>
            </div>
            <div class="line2 reason">{{ row.reason }}</div>
          </div>
          <div class="cell oven">
            <div class="muted small">归属 {{ row.ownOvenCode }}</div>
            <div class="muted small">交付截止 {{ fmt(row.deadline) }}</div>
          </div>
          <div class="cell acts">
            <el-button size="small" link type="primary" @click="moveToFront(row.orderId)">提到队首</el-button>
          </div>
        </div>
        <div class="empty" v-if="!unscheduledRows.length">全部待烤工单均已排入</div>
      </div>
    </div>

    <!-- 保存版本 -->
    <el-dialog v-model="saveDialog" title="保存排产版本" width="460px">
      <el-form label-width="90px">
        <el-form-item label="版本名">
          <el-input v-model="saveForm.name" placeholder="留空则按时间命名" />
        </el-form-item>
        <el-form-item label="操作员">
          <el-input v-model="saveForm.operator" placeholder="留空记为「操作员」" />
        </el-form-item>
      </el-form>
      <el-alert
        type="info" show-icon :closable="false"
        title="排产顺序只影响建议，不会改写工单状态与故障接管归属；若他人已保存更新版本或数据已变化，本次保存将被拒绝并提示刷新。"
      />
      <template #footer>
        <el-button @click="saveDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.bar h2 { margin: 0; font-size: 18px; }
.bar-acts { display: flex; gap: 10px; }
.ver-alert { margin-bottom: 12px; }

.zone { background: #fff; border: 1px solid var(--line); border-radius: 12px; margin-bottom: 14px; overflow: hidden; }
.zone-unscheduled { background: #fdf8f2; border-style: dashed; }
.zone-head {
  padding: 10px 14px; font-weight: 700; display: flex; align-items: center; justify-content: space-between;
  border-bottom: 1px solid var(--line); background: #fffdf8;
}
.zone-unscheduled .zone-head { background: #faf3e8; }
.zone-hint { font-size: 12px; color: var(--muted); font-weight: 400; }

.rows { padding: 10px; display: flex; flex-direction: column; gap: 8px; min-height: 46px; }
.row {
  display: flex; align-items: center; gap: 12px; padding: 8px 12px;
  border: 1px solid var(--line); border-radius: 10px; background: #fffdf8;
  cursor: grab; box-shadow: 0 1px 4px rgba(179, 107, 0, 0.08);
}
.row:active { cursor: grabbing; }
.row-unscheduled { background: #fbf6ec; border-color: #e8dcc8; }
.seq {
  width: 24px; height: 24px; border-radius: 50%; flex-shrink: 0;
  background: var(--primary); color: #fff; font-size: 12px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}
.row-unscheduled .seq { background: var(--muted); }
.cell.main { flex: 1; min-width: 0; }
.line1 { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.line2 { font-size: 12px; margin-top: 3px; }
.reason { color: #9a6a12; }
.badge { font-size: 12px; color: var(--muted); }
.cell.oven { width: 200px; flex-shrink: 0; }
.oven-code { color: var(--primary-deep); }
.cell.time { width: 300px; flex-shrink: 0; }
.cell.risk { width: 90px; flex-shrink: 0; text-align: center; }
.cell.acts { width: 110px; flex-shrink: 0; text-align: right; }
.muted { color: var(--muted); font-size: 12px; }
.small { font-size: 12px; }
.empty { color: var(--muted); font-size: 13px; text-align: center; padding: 14px 0; }
</style>
