<script setup>
// 生产排产：把「待烤」工单按交付截止/配方耗时/烤箱容量排成可解释的建议顺序。
// 关键口径：预计时间、烤箱分配、容量与逾期判定全部由后端计算——前端只维护优先级序列，
// 拖动后调 /schedule/preview 重算展示，保存走 /schedule/versions（版本号乐观并发）。
import { computed, onMounted, ref } from 'vue'
import api from '../api'
import { ElMessage } from 'element-plus'

const view = ref(null)
const loading = ref(false)
const previewing = ref(false)
const saving = ref(false)
const conflictMsg = ref('')
// 当前优先级序列（全部待烤工单 id：已排入在前、未排入在后），拖动只改它
const priorities = ref([])

const saveDialog = ref(false)
const operatorName = ref(localStorage.getItem('schedule-operator') || '')
const historyDialog = ref(false)
const versions = ref([])

const emit = defineEmits(['changed'])

const scheduled = computed(() => view.value?.scheduled || [])
const unscheduled = computed(() => view.value?.unscheduled || [])
const locked = computed(() => view.value?.locked || [])
const ovens = computed(() => view.value?.ovens || [])

const historical = computed(() => !!view.value?.historical)
const canEdit = computed(() => !!view.value && !historical.value)
const canSave = computed(() => canEdit.value && !!view.value && !view.value.saved)

const REASON_TYPES = {
  NO_OVEN: { label: '无可用烤箱', type: 'danger' },
  CAPACITY: { label: '容量不足', type: 'warning' },
  OVERDUE: { label: '预计逾期', type: 'danger' },
  RECIPE_MISSING: { label: '配方缺失', type: 'info' }
}
const reasonType = (t) => REASON_TYPES[t] || { label: t || '其他原因', type: 'info' }

const short = (t) => (t ? t.slice(5, 16) : '-')
const ovenTagType = (s) => (s === '空闲' ? 'success' : s === '占用' ? 'warning' : 'danger')

function syncPriorities() {
  priorities.value = [
    ...scheduled.value.map((i) => i.orderId),
    ...unscheduled.value.map((i) => i.orderId)
  ]
}

async function loadCurrent() {
  loading.value = true
  try {
    view.value = await api.scheduleCurrent()
    syncPriorities()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

// 拖动后防抖重算；mySeq 保证较慢的旧响应不会覆盖更新的拖动结果
let previewTimer = null
let previewSeq = 0
function queuePreview() {
  if (!canEdit.value) return
  clearTimeout(previewTimer)
  previewTimer = setTimeout(runPreview, 250)
}

async function runPreview() {
  if (!canEdit.value) return
  const mySeq = ++previewSeq
  previewing.value = true
  try {
    const v = await api.schedulePreview(priorities.value)
    if (mySeq !== previewSeq) return // 期间又有新拖动，丢弃这次旧结果
    view.value = v
    syncPriorities()
  } catch (e) {
    ElMessage.error(e.message)
    await loadCurrent()
  } finally {
    if (mySeq === previewSeq) previewing.value = false
  }
}

// ---------------- 拖动排序（仅调整优先级序列，效果由后端重算） ----------------
const draggingId = ref(null)
const dropBeforeId = ref(null)

function onDragStart(e, orderId) {
  if (!canEdit.value) { e.preventDefault(); return }
  draggingId.value = orderId
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData('text/plain', String(orderId))
}
function onCardOver(e, orderId) {
  if (draggingId.value == null || draggingId.value === orderId) return
  dropBeforeId.value = orderId
}
function onCardLeave(orderId) {
  if (dropBeforeId.value === orderId) dropBeforeId.value = null
}
// 拖到某张卡片上 = 插到它前面
function onDropOnCard(e, targetId) {
  e.preventDefault()
  const id = draggingId.value
  resetDrag()
  if (id == null || id === targetId) return
  const list = priorities.value.filter((x) => x !== id)
  const idx = list.indexOf(targetId)
  if (idx === -1) list.push(id)
  else list.splice(idx, 0, id)
  priorities.value = list
  queuePreview()
}
// 拖到建议顺序列表空白处 = 排到已排入区末尾
function onDropIntoSeqList(e) {
  e.preventDefault()
  const id = draggingId.value
  resetDrag()
  if (id == null) return
  const list = priorities.value.filter((x) => x !== id)
  list.splice(Math.min(scheduled.value.length, list.length), 0, id)
  priorities.value = list
  queuePreview()
}
// 拖到未排入区空白处 = 优先级降到最低（队尾）
function onDropIntoUnschedList(e) {
  e.preventDefault()
  const id = draggingId.value
  resetDrag()
  if (id == null) return
  const list = priorities.value.filter((x) => x !== id)
  list.push(id)
  priorities.value = list
  queuePreview()
}
function onDragEnd() { resetDrag() }
function resetDrag() { draggingId.value = null; dropBeforeId.value = null }

function move(orderId, dir) {
  const idx = priorities.value.indexOf(orderId)
  const to = idx + dir
  if (idx === -1 || to < 0 || to >= priorities.value.length) return
  const list = [...priorities.value]
  ;[list[idx], list[to]] = [list[to], list[idx]]
  priorities.value = list
  queuePreview()
}

// ---------------- 保存 / 历史版本 ----------------
function openSave() {
  operatorName.value = operatorName.value || localStorage.getItem('schedule-operator') || ''
  saveDialog.value = true
}

async function confirmSave() {
  saving.value = true
  try {
    const v = await api.scheduleSave({
      baseVersionNo: view.value.latestVersionNo,
      operatorName: operatorName.value,
      priorities: priorities.value
    })
    view.value = v
    syncPriorities()
    saveDialog.value = false
    conflictMsg.value = ''
    localStorage.setItem('schedule-operator', operatorName.value || '')
    ElMessage.success('已保存排产版本 #' + v.versionNo)
    emit('changed')
  } catch (e) {
    saveDialog.value = false
    if (e.status === 409) {
      // 并发保存冲突：只保留他人版本，刷新展示最新顺序 + 冲突提示
      conflictMsg.value = e.message
      await loadCurrent()
    } else {
      ElMessage.error(e.message)
    }
  } finally {
    saving.value = false
  }
}

async function openHistory() {
  historyDialog.value = true
  try {
    versions.value = await api.scheduleVersions()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function viewVersion(row) {
  try {
    view.value = await api.scheduleVersion(row.id)
    syncPriorities()
    historyDialog.value = false
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function backToLatest() {
  conflictMsg.value = ''
  await loadCurrent()
}

onMounted(loadCurrent)
</script>

<template>
  <div>
    <div class="bar">
      <h2>生产排产 · 建议顺序</h2>
      <div class="bar-acts">
        <el-button @click="openHistory">历史版本</el-button>
        <el-button v-if="historical" type="warning" @click="backToLatest">回到最新版本</el-button>
        <el-button :disabled="!canEdit" :loading="previewing" @click="runPreview">基于最新数据重算</el-button>
        <el-button type="primary" :disabled="!canSave" @click="openSave">保存排产版本</el-button>
      </div>
    </div>

    <!-- 版本状态条 -->
    <div class="version-line" v-if="view">
      <template v-if="view.saved">
        <el-tag effect="dark" type="success">版本 #{{ view.versionNo }}</el-tag>
        <span class="muted">{{ view.operatorName }} · {{ view.createdAt }} 保存</span>
      </template>
      <template v-else>
        <el-tag type="warning" effect="dark">未保存</el-tag>
        <span class="muted">基于最新数据实时计算的建议，保存后生成排产版本</span>
      </template>
      <el-tag v-if="view.stale" type="danger" effect="dark">已过期</el-tag>
      <el-tag v-if="historical" type="info">历史版本 · 只读</el-tag>
      <span class="muted" v-if="view.latestVersionNo">当前最新版本 #{{ view.latestVersionNo }}</span>
      <span class="muted">待烤 {{ view.pendingCount }} 单</span>
    </div>

    <!-- 过期提示：输入数据已变化，列出变化明细 -->
    <el-alert v-if="view && view.stale" type="warning" show-icon :closable="false" class="mb">
      <template #title>
        <div class="alert-line">
          <span>排产输入数据已变化，当前版本已过期（仅为建议，不影响工单实际状态与故障接管归属）：</span>
          <el-button size="small" type="warning" @click="runPreview">基于最新数据重算</el-button>
        </div>
        <div class="reason-list">
          <div v-for="(r, i) in view.staleReasons" :key="i">· {{ r }}</div>
        </div>
      </template>
    </el-alert>

    <!-- 并发保存冲突提示 -->
    <el-alert v-if="conflictMsg" type="error" show-icon :closable="false" class="mb">
      <template #title>
        <div class="alert-line">
          <span>{{ conflictMsg }}</span>
          <el-button size="small" type="danger" @click="backToLatest">查看最新版本</el-button>
        </div>
      </template>
    </el-alert>

    <div class="layout" v-loading="loading">
      <!-- 建议顺序 -->
      <div class="panel seq-panel">
        <div class="panel-head">
          <span>建议顺序（{{ scheduled.length }}）</span>
          <span class="hint" v-if="previewing">重算中…</span>
          <span class="hint" v-else-if="canEdit">拖动卡片或上移/下移调整优先级，仅「待烤」工单可调整</span>
        </div>
        <div class="seq-list" :class="{ previewing }" @dragover.prevent @drop="onDropIntoSeqList">
          <div
            v-for="item in scheduled"
            :key="item.orderId"
            class="seq-card"
            :class="{ 'drop-before': dropBeforeId === item.orderId, dragging: draggingId === item.orderId }"
            :draggable="canEdit"
            @dragstart="onDragStart($event, item.orderId)"
            @dragover.prevent="onCardOver($event, item.orderId)"
            @dragleave="onCardLeave(item.orderId)"
            @drop.stop="onDropOnCard($event, item.orderId)"
            @dragend="onDragEnd"
          >
            <div class="seq-head">
              <span class="seq-no">#{{ item.seq }}</span>
              <span class="title">工单 #{{ item.orderId }} · {{ item.recipeName }}</span>
              <el-tag size="small" effect="dark" type="warning">{{ item.ovenCode }}</el-tag>
            </div>
            <div class="meta">
              烤盘 {{ item.trays }}/{{ item.ovenCapacity }} · 时长 {{ item.bakeMinutes }} 分钟 · {{ item.capacityNote }}
            </div>
            <div class="meta">
              预计 {{ short(item.estStartTime) }} → {{ short(item.estFinishTime) }}
              <template v-if="item.dueTime">
                · 截止 {{ short(item.dueTime) }}
                <el-tag v-if="item.deadlineRisk === '紧张'" size="small" type="warning" effect="dark">截止紧张</el-tag>
              </template>
              <template v-else> · 无交付截止</template>
            </div>
            <div class="reason">{{ item.reason }}</div>
            <div class="card-acts" v-if="canEdit">
              <el-button size="small" text @click="move(item.orderId, -1)">上移</el-button>
              <el-button size="small" text @click="move(item.orderId, 1)">下移</el-button>
            </div>
          </div>
          <el-empty v-if="!scheduled.length" description="暂无可排入的待烤工单" :image-size="60" />
        </div>
      </div>

      <!-- 侧栏 -->
      <div class="side">
        <div class="panel">
          <div class="panel-head">
            <span>未排入区（{{ unscheduled.length }}）</span>
            <span class="hint">不满足排入条件，不会伪装成已安排</span>
          </div>
          <div class="side-list" @dragover.prevent @drop="onDropIntoUnschedList">
            <div
              v-for="item in unscheduled"
              :key="item.orderId"
              class="unsched-card"
              :class="{ 'drop-before': dropBeforeId === item.orderId, dragging: draggingId === item.orderId }"
              :draggable="canEdit"
              @dragstart="onDragStart($event, item.orderId)"
              @dragover.prevent="onCardOver($event, item.orderId)"
              @dragleave="onCardLeave(item.orderId)"
              @drop.stop="onDropOnCard($event, item.orderId)"
              @dragend="onDragEnd"
            >
              <div class="seq-head">
                <span class="title">工单 #{{ item.orderId }} · {{ item.recipeName }}</span>
                <el-tag size="small" :type="reasonType(item.reasonType).type" effect="dark">
                  {{ reasonType(item.reasonType).label }}
                </el-tag>
              </div>
              <div class="meta">
                烤盘 {{ item.trays }}
                <template v-if="item.dueTime"> · 截止 {{ short(item.dueTime) }}</template>
                · 原挂 {{ item.originOvenCode || '-' }}<template v-if="item.originOvenStatus">（{{ item.originOvenStatus }}）</template>
              </div>
              <div class="reason warn">{{ item.reason }}</div>
            </div>
            <div v-if="!unscheduled.length" class="empty-tip">全部待烤工单均已排入</div>
            <div class="hint" v-if="canEdit && unscheduled.length">可拖入左侧建议顺序提高优先级，保存后由后端重新判定</div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head"><span>烤箱容量</span></div>
          <div class="oven-row" v-for="o in ovens" :key="o.id">
            <span class="oven-code">{{ o.code }}</span>
            <span class="muted">容量 {{ o.capacity }}</span>
            <el-tag size="small" :type="ovenTagType(o.status)">{{ o.status }}</el-tag>
            <span class="muted grow">{{ o.note }}</span>
            <el-tag size="small" effect="plain" v-if="o.assignedCount">排入 {{ o.assignedCount }}</el-tag>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head"><span>进行中 / 已完成 · 不可调整（{{ locked.length }}）</span></div>
          <div class="locked-list">
            <span v-for="l in locked" :key="l.orderId" class="locked-chip">
              #{{ l.orderId }} {{ l.recipeName }}
              <el-tag size="small" :type="l.status === '烘焙中' ? 'danger' : 'info'">{{ l.status }}</el-tag>
              {{ l.ovenCode }} 🔒
            </span>
            <span v-if="!locked.length" class="empty-tip">暂无</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 保存排产版本 -->
    <el-dialog v-model="saveDialog" title="保存排产版本" width="460px">
      <el-form label-width="100px">
        <el-form-item label="操作员">
          <el-input v-model="operatorName" placeholder="你的名字（随版本保存）" maxlength="32" style="width: 220px" />
        </el-form-item>
        <el-form-item label="版本说明">
          <div class="muted">
            排入 {{ scheduled.length }} 单 · 未排入 {{ unscheduled.length }} 单
            <template v-if="view && view.latestVersionNo"> · 基于版本 #{{ view.latestVersionNo }}</template>
            <template v-else> · 首个版本</template>
          </div>
        </el-form-item>
        <el-alert
          type="info" :closable="false"
          title="排产只是建议：保存不会改写工单状态、挂炉归属或故障接管结果；若他人已先保存，本次将提示冲突并刷新为最新顺序。"
        />
      </el-form>
      <template #footer>
        <el-button @click="saveDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="confirmSave">保存版本</el-button>
      </template>
    </el-dialog>

    <!-- 历史版本 -->
    <el-dialog v-model="historyDialog" title="排产版本历史" width="680px">
      <el-table :data="versions" border size="small">
        <el-table-column label="版本" width="110">
          <template #default="{ row }">
            #{{ row.versionNo }}
            <el-tag v-if="row.current" size="small" type="success" style="margin-left: 4px">当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="保存时间" width="160" />
        <el-table-column prop="operatorName" label="操作员" width="100" />
        <el-table-column label="排入/未排入" width="100">
          <template #default="{ row }">{{ row.scheduledCount }} / {{ row.unscheduledCount }}</template>
        </el-table-column>
        <el-table-column label="基于版本" width="90">
          <template #default="{ row }">{{ row.baseVersionNo ? '#' + row.baseVersionNo : '-' }}</template>
        </el-table-column>
        <el-table-column label="操作">
          <template #default="{ row }">
            <el-button size="small" @click="viewVersion(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; flex-wrap: wrap; gap: 8px; }
.bar h2 { margin: 0; font-size: 18px; }
.bar-acts { display: flex; gap: 8px; }
.mb { margin-bottom: 10px; }
.muted { color: var(--muted); font-size: 12px; }
.hint { font-size: 12px; color: var(--muted); }
.version-line { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.alert-line { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.reason-list { margin-top: 6px; font-size: 12px; line-height: 1.7; }

.layout { display: grid; grid-template-columns: minmax(0, 1fr) 360px; gap: 14px; align-items: start; }
.panel { background: #fff; border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
.panel-head {
  padding: 10px 14px; font-weight: 700; background: var(--primary-wash); color: var(--primary-deep);
  display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap;
}
.panel-head .hint { font-weight: 400; }

.seq-list { padding: 10px; min-height: 200px; display: flex; flex-direction: column; gap: 10px; transition: opacity 0.15s; }
.seq-list.previewing { opacity: 0.55; }
.seq-card {
  border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; background: #fffdf8;
  box-shadow: 0 1px 4px rgba(179, 107, 0, 0.08); cursor: grab;
}
.seq-card.dragging, .unsched-card.dragging { opacity: 0.4; }
.seq-card.drop-before, .unsched-card.drop-before { box-shadow: 0 -3px 0 0 var(--primary); }
.seq-head { display: flex; align-items: center; gap: 8px; }
.seq-no {
  min-width: 26px; height: 22px; border-radius: 6px; background: var(--primary); color: #fff;
  font-size: 12px; font-weight: 700; display: inline-flex; align-items: center; justify-content: center; padding: 0 6px;
}
.title { font-weight: 600; flex: 1; }
.meta { font-size: 12px; color: var(--muted); margin-top: 4px; display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.reason { font-size: 12px; color: var(--primary-deep); margin-top: 6px; line-height: 1.5; }
.reason.warn { color: #b4443e; }
.card-acts { margin-top: 4px; }

.side { display: flex; flex-direction: column; gap: 14px; }
.side-list { padding: 10px; min-height: 80px; display: flex; flex-direction: column; gap: 10px; }
.unsched-card {
  border: 1px dashed #e3a3a3; border-radius: 10px; padding: 10px 12px; background: #fff7f5; cursor: grab;
}
.oven-row {
  display: flex; align-items: center; gap: 8px; padding: 8px 14px; font-size: 13px;
  border-bottom: 1px solid var(--line);
}
.oven-row:last-child { border-bottom: none; }
.oven-code { font-weight: 700; color: var(--primary-deep); }
.grow { flex: 1; }
.locked-list { padding: 10px 14px; display: flex; flex-wrap: wrap; gap: 8px; }
.locked-chip {
  display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted);
  border: 1px solid var(--line); border-radius: 8px; padding: 4px 8px; background: #faf8f4;
}
.empty-tip { color: var(--muted); font-size: 13px; text-align: center; padding: 14px 0; }
</style>
