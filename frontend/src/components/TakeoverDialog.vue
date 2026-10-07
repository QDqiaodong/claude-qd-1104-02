<script setup>
// 故障接管弹窗：查看故障炉上的待烤/烘焙中工单，为每张工单选替代烤箱，
// 预览校验 → 二次确认 → 整批提交。失败时整批不落库，弹窗保留选择并展示逐单原因。
import { computed, ref, watch } from 'vue'
import api from '../api'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  sourceOvenId: { type: Number, default: null }
})
const emit = defineEmits(['update:modelValue', 'taken'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const loading = ref(false)
const submitting = ref(false)
const preview = ref(null)
// 每行：{ orderId, checked, targetOvenId }
const rows = ref([])
const errorText = ref('')

async function load() {
  if (!props.sourceOvenId) return
  loading.value = true
  errorText.value = ''
  try {
    const pv = await api.takeoverPreview(props.sourceOvenId)
    preview.value = pv
    rows.value = pv.orders.map((o) => ({ orderId: o.id, checked: true, targetOvenId: null }))
  } catch (e) {
    preview.value = null
    rows.value = []
    errorText.value = e.message
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.sourceOvenId],
  ([open]) => {
    if (open) {
      errorText.value = ''
      load()
    }
  }
)

const rowMap = computed(() => Object.fromEntries(rows.value.map((r) => [r.orderId, r])))
const orderMap = computed(() =>
  Object.fromEntries((preview.value?.orders || []).map((o) => [o.id, o]))
)

function targetView(id) {
  return (preview.value?.targetOvens || []).find((t) => t.id === id)
}

// 该工单在当前选择下，某候选炉是否可选；不可选时返回原因（el-option 置灰 + 文案）
function optionState(order, target) {
  if (target.status !== '空闲') {
    return { ok: false, reason: '非空闲（' + target.status + '）' }
  }
  if (order.trays > target.capacity) {
    return { ok: false, reason: '容量不足 ' + target.capacity + '<' + order.trays }
  }
  // 烘焙中工单：候选炉本身必须没有烘焙中工单（预览口径）
  if (order.status === '烘焙中' && target.reason && !target.eligible) {
    return { ok: false, reason: target.reason }
  }
  return { ok: true, reason: '' }
}

// 本批中已占用“烘焙中槽位”的目标炉（两张烘焙中工单选同一炉 → 后一张报错）
const bakingTargetOnce = computed(() => {
  const seen = new Set()
  const dup = new Set()
  for (const r of rows.value) {
    if (!r.checked || r.targetOvenId == null) continue
    const o = orderMap.value[r.orderId]
    if (o && o.status === '烘焙中') {
      if (seen.has(r.targetOvenId)) dup.add(r.targetOvenId)
      seen.add(r.targetOvenId)
    }
  }
  return dup
})

// 逐行前端校验信息（与后端口径一致，仅用于即时提示；最终裁决以后端为准）
const rowIssues = computed(() => {
  const map = {}
  for (const r of rows.value) {
    if (!r.checked) continue
    const o = orderMap.value[r.orderId]
    if (r.targetOvenId == null) {
      map[r.orderId] = '请选择替代烤箱'
      continue
    }
    const t = targetView(r.targetOvenId)
    if (!t) { map[r.orderId] = '替代烤箱不存在'; continue }
    const st = optionState(o, t)
    if (!st.ok) { map[r.orderId] = st.reason; continue }
    if (o.status === '烘焙中' && bakingTargetOnce.value.has(t.id)) {
      map[r.orderId] = '同一替代炉本批已选另一张烘焙中工单（一炉一张）'
    }
  }
  return map
})

const selectedRows = computed(() => rows.value.filter((r) => r.checked))
const hasIssue = computed(() => Object.keys(rowIssues.value).length > 0)
const bakingSelected = computed(() =>
  selectedRows.value.some((r) => orderMap.value[r.orderId]?.status === '烘焙中'))

function tagType(status) {
  return status === '烘焙中' ? 'danger' : 'warning'
}

async function submit() {
  errorText.value = ''
  if (!selectedRows.value.length) {
    ElMessage.warning('请勾选至少一张工单')
    return
  }
  if (hasIssue.value) {
    ElMessage.error('存在未通过校验的工单，请按每行提示修正后再提交')
    return
  }
  const items = selectedRows.value.map((r) => ({
    orderId: r.orderId,
    targetOvenId: r.targetOvenId
  }))
  const pendingCount = items.length - bakingTargetCount.value
  try {
    await ElMessageBox.confirm(
      `本次接管将从 ${preview.value.sourceOvenCode} 迁移 ${items.length} 张工单` +
      `（待烤 ${pendingCount} 张 / 烘焙中 ${bakingTargetCount.value} 张）。` +
      (bakingSelected.value
        ? '\n其中包含「烘焙中」工单：换炉后保留原开始时间续烤，属高风险操作。'
        : '\n待烤工单迁移后仍为待烤，到替代炉后再开始烘焙。') +
      '\n任一张工单不满足条件时整批不落库。确认提交？',
      '确认故障接管',
      { confirmButtonText: '确认接管', cancelButtonText: '再看看', type: bakingSelected.value ? 'warning' : 'info' }
    )
  } catch { return }

  submitting.value = true
  try {
    const result = await api.takeover(preview.value.sourceOvenId, items)
    const detail = result.moved.map((m) =>
      `· 工单 #${m.orderId}（${m.status}，${m.trays}盘）→ ${m.targetOvenCode}` +
      (m.status === '烘焙中' && m.startTime ? `，原开始时间 ${m.startTime}` : '')
    ).join('<br/>')
    ElNotification.success({
      title: `接管成功 · 共迁移 ${result.movedCount} 张工单`,
      dangerouslyUseHTMLString: true,
      message: detail,
      duration: 6000
    })
    visible.value = false
    emit('taken')
  } catch (e) {
    // 整批失败：不关闭弹窗，保留全部选择，逐单原因就地展示，便于修正后重试
    errorText.value = e.message
  } finally {
    submitting.value = false
  }
}

const bakingTargetCount = computed(() =>
  selectedRows.value.filter((r) => orderMap.value[r.orderId]?.status === '烘焙中').length)
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="preview ? `故障接管 · ${preview.sourceOvenCode}` : '故障接管'"
    width="860px"
    :close-on-click-modal="false"
  >
    <div v-loading="loading">
      <el-alert
        v-if="preview"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
        :title="`故障炉 ${preview.sourceOvenCode} 已停止生产，请把下面的工单迁往空闲替代烤箱；未迁移的工单仍保留在该炉，修复后可再次接管。`"
      />

      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
        title="整批接管失败，无任何工单落库（故障炉状态、工单归属、替代炉占用均保持原样）："
      >
        <div class="err-box">{{ errorText }}</div>
      </el-alert>

      <el-empty v-if="!loading && preview && !preview.orders.length" description="该故障炉当前没有待烤/烘焙中工单需要接管" />

      <el-table
        v-if="preview && preview.orders.length"
        :data="preview.orders"
        border
        size="small"
        row-key="id"
        :row-class-name="(d) => d.row.status === '烘焙中' ? 'risk-high-row' : 'risk-mid-row'"
      >
        <el-table-column width="46">
          <template #default="{ row }">
            <el-checkbox v-model="rowMap[row.id].checked" />
          </template>
        </el-table-column>
        <el-table-column label="工单" width="80">
          <template #default="{ row }">#{{ row.id }}</template>
        </el-table-column>
        <el-table-column label="状态 / 风险" width="150">
          <template #default="{ row }">
            <el-tag :type="tagType(row.status)" size="small" effect="dark">{{ row.status }}</el-tag>
            <el-tag
              size="small"
              :type="row.riskLevel === 'high' ? 'danger' : 'warning'"
              effect="plain"
              style="margin-left: 6px"
            >{{ row.riskLevel === 'high' ? '高风险' : '中风险' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="面包 / 烤盘" width="150">
          <template #default="{ row }">
            <div>{{ row.recipeName }}</div>
            <div class="muted">{{ row.trays }} 盘</div>
          </template>
        </el-table-column>
        <el-table-column label="风险说明" min-width="200">
          <template #default="{ row }">
            <span :class="row.riskLevel === 'high' ? 'risk-high-text' : 'risk-mid-text'">{{ row.riskText }}</span>
          </template>
        </el-table-column>
        <el-table-column label="替代烤箱" width="210">
          <template #default="{ row }">
            <el-select
              v-model="rowMap[row.id].targetOvenId"
              :disabled="!rowMap[row.id].checked"
              placeholder="选择替代烤箱"
              size="small"
              style="width: 188px"
            >
              <el-option
                v-for="t in preview.targetOvens"
                :key="t.id"
                :label="`${t.code}（容量${t.capacity}）${optionState(row, t).ok ? '' : '· ' + optionState(row, t).reason}`"
                :value="t.id"
                :disabled="!optionState(row, t).ok"
              />
            </el-select>
            <div v-if="rowMap[row.id].checked && rowIssues[row.id]" class="row-err">
              {{ rowIssues[row.id] }}
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="preview && preview.orders.length" class="summary">
        已选 <b>{{ selectedRows.length }}</b> 张
        <span class="muted">（待烤工单迁移后仍为待烤；烘焙中工单保留状态与原开始时间，原炉计时不重置）</span>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="danger"
        :loading="submitting"
        :disabled="!selectedRows.length || hasIssue"
        @click="submit"
      >整批接管（{{ selectedRows.length }}）</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.muted { color: var(--muted); font-size: 12px; }
.err-box {
  white-space: pre-wrap; line-height: 1.7; font-size: 12px;
  max-height: 220px; overflow: auto; margin-top: 4px;
}
.risk-high-text { color: #c45656; font-size: 12px; }
.risk-mid-text { color: #b8841f; font-size: 12px; }
.row-err { color: #c45656; font-size: 12px; margin-top: 2px; line-height: 1.3; }
.summary { margin-top: 10px; font-size: 13px; }
:deep(.risk-high-row) { background: #fdf0f0 !important; }
:deep(.risk-mid-row) { background: #fdf8ec; }
</style>
