<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import api from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { openTakeover } from '../utils/bus'

const list = ref([])
const orders = ref([])
const recipes = ref([])
const dialog = ref(false)
const editing = ref(false)
const form = reactive({ id: null, code: '', capacity: 1, status: '空闲' })

// 烤箱详情抽屉
const drawer = ref(false)
const detail = ref(null)

const ovenStatusType = (s) => (s === '空闲' ? 'success' : s === '占用' ? 'warning' : 'danger')

const recipeMap = computed(() => Object.fromEntries(recipes.value.map((r) => [r.id, r])))

// 故障炉 + 上面尚未完成的工单（异常提示）
const brokenOvens = computed(() => list.value.filter((o) => o.status === '故障'))
const stuckMap = computed(() => {
  const map = {}
  for (const o of orders.value) {
    if (o.status === '待烤' || o.status === '烘焙中') {
      ;(map[o.ovenId] || (map[o.ovenId] = [])).push(o)
    }
  }
  return map
})

async function load() {
  const [ov, od, rp] = await Promise.all([
    api.listOvens(), api.listBakeOrders(), api.listBreadRecipes()
  ])
  list.value = ov
  orders.value = od
  recipes.value = rp
  if (detail.value) {
    const fresh = ov.find((x) => x.id === detail.value.id)
    if (fresh) detail.value = fresh
  }
}

function openCreate() {
  editing.value = false
  Object.assign(form, { id: null, code: '', capacity: 1, status: '空闲' })
  dialog.value = true
}

function openEdit(row) {
  editing.value = true
  Object.assign(form, { id: row.id, code: row.code, capacity: row.capacity, status: row.status })
  dialog.value = true
}

function openDetail(row) {
  detail.value = row
  drawer.value = true
}

async function submit() {
  try {
    if (editing.value) {
      await api.updateOven(form.id, { code: form.code, capacity: form.capacity, status: form.status })
    } else {
      await api.createOven({ code: form.code, capacity: form.capacity, status: form.status })
    }
    dialog.value = false
    await load()
    emitChanged()
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doBreak(row) {
  try {
    await api.breakOven(row.id)
    await load(); emitChanged()
    const stuck = stuckMap.value[row.id]
    ElMessageBox.confirm(
      `${row.code} 已置为故障。` +
      (stuck && stuck.length
        ? `该炉上有 ${stuck.length} 张待烤/烘焙中工单停滞，是否立即发起故障接管，把它们迁往替代烤箱？`
        : '该炉当前没有停滞的工单。'),
      '烤箱已故障',
      {
        confirmButtonText: stuck && stuck.length ? '发起故障接管' : '知道了',
        cancelButtonText: '稍后处理',
        showCancelButton: !!(stuck && stuck.length),
        type: stuck && stuck.length ? 'warning' : 'info'
      }
    ).then(() => {
      if (stuck && stuck.length) openTakeover(row.id)
    }).catch(() => {})
  } catch (e) { ElMessage.error(e.message) }
}

async function doRepair(row) {
  const stuck = (stuckMap.value[row.id] || [])
  const pending = stuck.filter((o) => o.status === '待烤').length
  const baking = stuck.filter((o) => o.status === '烘焙中').length
  try {
    await ElMessageBox.confirm(
      `确认将 ${row.code} 修复为「空闲」？` +
      (stuck.length
        ? `\n该炉仍保留 ${stuck.length} 张未迁移工单（待烤 ${pending} / 烘焙中 ${baking}），` +
          '修复后这些工单仍归属本炉；若再次故障可继续接管。'
        : ''),
      '修复烤箱',
      { confirmButtonText: '修复为空闲', cancelButtonText: '取消', type: 'warning' }
    )
  } catch { return }
  try {
    await api.repairOven(row.id)
    await load(); emitChanged()
    ElMessage.success(stuck.length
      ? `已修复为空闲，${stuck.length} 张未迁移工单仍保留在 ${row.code}`
      : '已修复为空闲')
  } catch (e) { ElMessage.error(e.message) }
}

function tagType(status) {
  return status === '烘焙中' ? 'danger' : status === '待烤' ? 'warning' : 'info'
}

const emit = defineEmits(['changed'])
function emitChanged() { emit('changed') }

onMounted(load)
</script>

<template>
  <div>
    <div class="bar">
      <h2>烤箱设备</h2>
      <el-button type="primary" @click="openCreate">新增烤箱</el-button>
    </div>

    <!-- 异常提示：故障炉与其停滞工单 -->
    <el-alert
      v-for="o in brokenOvens"
      :key="'brk-' + o.id"
      type="error"
      show-icon
      :closable="false"
      style="margin-bottom: 10px"
    >
      <template #title>
        <div class="alert-line">
          <span>
            烤箱 <b>{{ o.code }}</b> 处于故障状态，
            上面停滞 {{ (stuckMap[o.id] || []).length }} 张待烤/烘焙中工单，需尽快接管迁移
          </span>
          <span>
            <el-button size="small" type="danger" @click="openTakeover(o.id)">故障接管</el-button>
            <el-button size="small" @click="openDetail(o)">查看详情</el-button>
            <el-button size="small" type="success" @click="doRepair(o)">修复为空闲</el-button>
          </span>
        </div>
      </template>
    </el-alert>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="code" label="炉号" width="120">
        <template #default="{ row }">
          <el-link type="primary" @click="openDetail(row)">{{ row.code }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="capacity" label="容量(同时烤盘数)" width="160" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }"><el-tag :type="ovenStatusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="停滞工单" width="180">
        <template #default="{ row }">
          <template v-if="(stuckMap[row.id] || []).length">
            <el-tag size="small" type="danger">{{ (stuckMap[row.id] || []).length }} 张待处理</el-tag>
            <el-button size="small" link type="danger" @click="openDetail(row)">查看</el-button>
          </template>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="320">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row)">详情</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="warning" v-if="row.status !== '故障'" @click="doBreak(row)">置故障</el-button>
          <el-button size="small" type="danger" v-if="row.status === '故障'" @click="openTakeover(row.id)">故障接管</el-button>
          <el-button size="small" type="success" v-if="row.status === '故障'" @click="doRepair(row)">修复</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="editing ? '编辑烤箱' : '新增烤箱'" width="420px">
      <el-form label-width="110px">
        <el-form-item label="炉号">
          <el-input v-model="form.code" placeholder="如 OV-05" />
        </el-form-item>
        <el-form-item label="容量(烤盘数)">
          <el-input-number v-model="form.capacity" :min="1" :max="99" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 160px">
            <el-option label="空闲" value="空闲" />
            <el-option label="占用" value="占用" />
            <el-option label="故障" value="故障" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 烤箱详情 -->
    <el-drawer v-model="drawer" :title="detail ? `烤箱详情 · ${detail.code}` : '烤箱详情'" size="520px">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="ID">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="炉号">{{ detail.code }}</el-descriptions-item>
          <el-descriptions-item label="容量（同时烤盘数）">{{ detail.capacity }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="ovenStatusType(detail.status)">{{ detail.status }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div class="drawer-acts">
          <el-button v-if="detail.status !== '故障'" type="warning" @click="doBreak(detail)">置为故障</el-button>
          <el-button v-if="detail.status === '故障'" type="danger" @click="openTakeover(detail.id)">故障接管</el-button>
          <el-button v-if="detail.status === '故障'" type="success" @click="doRepair(detail)">修复为空闲</el-button>
        </div>

        <h3 class="sub">炉上工单</h3>
        <el-table :data="orders.filter((o) => o.ovenId === detail.id)" border size="small">
          <el-table-column label="#" width="56">
            <template #default="{ row }">#{{ row.id }}</template>
          </el-table-column>
          <el-table-column label="面包">
            <template #default="{ row }">{{ recipeMap[row.recipeId] ? recipeMap[row.recipeId].name : '?' }}</template>
          </el-table-column>
          <el-table-column label="烤盘" prop="trays" width="64" />
          <el-table-column label="状态" width="92">
            <template #default="{ row }"><el-tag size="small" :type="tagType(row.status)">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="开始时间" min-width="150">
            <template #default="{ row }">{{ row.startTime || '-' }}</template>
          </el-table-column>
          <template #empty>炉上暂无工单</template>
        </el-table>

        <el-alert
          v-if="detail.status === '故障' && (stuckMap[detail.id] || []).length"
          type="error" show-icon :closable="false" style="margin-top: 14px"
          title="故障炉上的待烤/烘焙中工单已停滞：点击「故障接管」可批量迁往替代烤箱；未迁移的工单修复后仍保留在本炉。"
        />
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.bar h2 { margin: 0; font-size: 18px; }
.alert-line { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.muted { color: var(--muted); font-size: 12px; }
.drawer-acts { margin: 16px 0; display: flex; gap: 10px; }
.sub { font-size: 15px; margin: 6px 0 10px; }
</style>
