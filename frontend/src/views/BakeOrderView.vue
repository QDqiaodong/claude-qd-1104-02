<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import api from '../api'
import { ElMessage } from 'element-plus'
import bus, { openTakeover } from '../utils/bus'

const orders = ref([])
const ovens = ref([])
const recipes = ref([])

const COLS = ['待烤', '烘焙中', '已出炉', '已质检']

const dialog = ref(false)
const form = reactive({ ovenId: null, recipeId: null, trays: 1, dueTime: null })

// 待烤工单改期/改盘：烤盘数与交付截止是排产输入，修改后已保存的排产版本会过期
const editDialog = ref(false)
const editForm = reactive({ id: null, trays: 1, dueTime: null })

const ovenMap = computed(() => Object.fromEntries(ovens.value.map((o) => [o.id, o])))
const recipeMap = computed(() => Object.fromEntries(recipes.value.map((r) => [r.id, r])))

const byStatus = computed(() => {
  const map = { '待烤': [], '烘焙中': [], '已出炉': [], '已质检': [] }
  for (const o of orders.value) (map[o.status] || (map[o.status] = [])).push(o)
  return map
})

const selectableOvens = computed(() => ovens.value.filter((o) => o.status !== '故障'))
const selectableRecipes = computed(() => recipes.value.filter((r) => r.status === '上架'))

// 故障炉清单（用于看板顶部异常提示 + 卡片上的故障标红）
const brokenOvens = computed(() => ovens.value.filter((o) => o.status === '故障'))
function isBroken(ovenId) {
  const o = ovenMap.value[ovenId]
  return o && o.status === '故障'
}
function stuckOrders(ovenId) {
  return orders.value.filter((o) =>
    o.ovenId === ovenId && (o.status === '待烤' || o.status === '烘焙中'))
}

const ovenCapacity = computed(() => {
  const o = ovenMap.value[form.ovenId]
  return o ? o.capacity : null
})

async function load() {
  const [od, ov, rp] = await Promise.all([api.listBakeOrders(), api.listOvens(), api.listBreadRecipes()])
  orders.value = od
  ovens.value = ov
  recipes.value = rp
}

async function refresh() { await load(); emitChanged() }

function openCreate() {
  Object.assign(form, { ovenId: null, recipeId: null, trays: 1, dueTime: null })
  dialog.value = true
}

async function submit() {
  try {
    await api.createBakeOrder({ ovenId: form.ovenId, recipeId: form.recipeId, trays: form.trays, dueTime: form.dueTime })
    dialog.value = false
    await refresh()
    ElMessage.success('已排产')
  } catch (e) { ElMessage.error(e.message) }
}

function openEdit(o) {
  Object.assign(editForm, { id: o.id, trays: o.trays, dueTime: o.dueTime || null })
  editDialog.value = true
}

async function submitEdit() {
  try {
    await api.updateBakeOrder(editForm.id, { trays: editForm.trays, dueTime: editForm.dueTime })
    editDialog.value = false
    await refresh()
    ElMessage.success('已更新；已保存的排产版本会标记为已过期，可到「排产」页重算')
  } catch (e) { ElMessage.error(e.message) }
}

async function act(id, fn, label) {
  try { await fn(id); await refresh(); ElMessage.success(label + '成功') }
  catch (e) { ElMessage.error(e.message) }
}

const emit = defineEmits(['changed'])
function emitChanged() { emit('changed') }

// 接管成功后看板重拉（App 层会整体重挂载，这里再订阅一次保证视图内数据一致）
const offTaken = bus.on('takeover:taken', () => load())

onMounted(load)
onUnmounted(offTaken)
</script>

<template>
  <div>
    <div class="bar">
      <h2>烘焙工单 · 看板</h2>
      <el-button type="primary" @click="openCreate">排产新工单</el-button>
    </div>

    <!-- 异常提示：故障炉上的待烤/烘焙中工单 -->
    <el-alert
      v-for="o in brokenOvens"
      :key="'brk-' + o.id"
      type="error" show-icon :closable="false"
      style="margin-bottom: 10px"
    >
      <template #title>
        <div class="alert-line">
          <span>
            烤箱 <b>{{ o.code }}</b> 故障，
            <span class="risk-high-text">{{ stuckOrders(o.id).filter((x) => x.status === '烘焙中').length }} 张烘焙中</span>
            （保留原开始时间续烤，高风险）、
            <span class="risk-mid-text">{{ stuckOrders(o.id).filter((x) => x.status === '待烤').length }} 张待烤</span>
            （迁移后仍待烤，中风险）工单停滞，请接管
          </span>
          <el-button size="small" type="danger" @click="openTakeover(o.id)">故障接管</el-button>
        </div>
      </template>
    </el-alert>

    <div class="board">
      <div class="col" v-for="c in COLS" :key="c">
        <div class="col-head" :class="'col-' + c">{{ c }}<span class="cnt">{{ byStatus[c].length }}</span></div>
        <div class="col-body">
          <div class="card" v-for="o in byStatus[c]" :key="o.id" :class="{ 'card-broken': isBroken(o.ovenId) }">
            <div class="card-top">
              <span class="badge">#{{ o.id }}</span>
              <span class="oven">
                {{ ovenMap[o.ovenId] ? ovenMap[o.ovenId].code : '?' }}
                <el-tag v-if="isBroken(o.ovenId)" size="small" type="danger" effect="dark" style="margin-left: 4px">炉故障</el-tag>
              </span>
            </div>
            <div class="recipe">{{ recipeMap[o.recipeId] ? recipeMap[o.recipeId].name : '?' }}</div>
            <div class="meta">烤盘 {{ o.trays }} / 容量 {{ ovenMap[o.ovenId] ? ovenMap[o.ovenId].capacity : '-' }}</div>
            <div class="meta" v-if="o.dueTime">截止 {{ o.dueTime }}</div>
            <div class="meta" v-if="o.startTime">开始 {{ o.startTime }}</div>
            <div
              v-if="isBroken(o.ovenId)"
              class="risk-tip"
              :class="c === '烘焙中' ? 'risk-high' : 'risk-mid'"
            >
              <template v-if="c === '烘焙中'">⚠ 烘焙中工单随故障炉停滞，接管换炉将保留原开始时间续烤（高风险）</template>
              <template v-else>⚠ 待烤工单随故障炉停滞，接管迁移后仍为待烤（中风险）</template>
            </div>
            <div class="acts">
              <el-button size="small" type="primary" v-if="c === '待烤'" @click="act(o.id, api.startBakeOrder, '开始烘焙')">开始</el-button>
              <el-button size="small" v-if="c === '待烤'" @click="openEdit(o)">改期</el-button>
              <el-button size="small" type="warning" v-if="c === '烘焙中'" @click="act(o.id, api.doneBakeOrder, '出炉')">出炉</el-button>
              <el-button size="small" type="success" v-if="c === '已出炉'" @click="act(o.id, api.inspectBakeOrder, '质检')">质检</el-button>
              <el-button size="small" type="danger" v-if="isBroken(o.ovenId) && (c === '待烤' || c === '烘焙中')" @click="openTakeover(o.ovenId)">故障接管</el-button>
              <span class="done-tip" v-if="c === '已质检'">可出货</span>
            </div>
          </div>
          <div class="empty" v-if="!byStatus[c].length">暂无</div>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialog" title="排产新工单" width="440px">
      <el-form label-width="100px">
        <el-form-item label="烤箱">
          <el-select v-model="form.ovenId" placeholder="选择烤箱" style="width: 220px">
            <el-option v-for="o in selectableOvens" :key="o.id" :label="o.code + '（容量' + o.capacity + '）'" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="配方">
          <el-select v-model="form.recipeId" placeholder="选择上架配方" style="width: 220px">
            <el-option v-for="r in selectableRecipes" :key="r.id" :label="r.name + '（需' + r.needTrays + '盘）'" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="实际烤盘数">
          <el-input-number v-model="form.trays" :min="1" :max="ovenCapacity || 99" />
          <span class="hint" v-if="ovenCapacity"> ≤ 容量 {{ ovenCapacity }}</span>
        </el-form-item>
        <el-form-item label="交付截止">
          <el-date-picker
            v-model="form.dueTime"
            type="datetime"
            placeholder="可选，留空表示无截止"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 220px"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">排产</el-button>
      </template>
    </el-dialog>

    <!-- 待烤工单改期/改盘 -->
    <el-dialog v-model="editDialog" title="调整待烤工单" width="440px">
      <el-form label-width="100px">
        <el-form-item label="工单">
          <span>#{{ editForm.id }}</span>
        </el-form-item>
        <el-form-item label="实际烤盘数">
          <el-input-number v-model="editForm.trays" :min="1" :max="99" />
        </el-form-item>
        <el-form-item label="交付截止">
          <el-date-picker
            v-model="editForm.dueTime"
            type="datetime"
            placeholder="留空则不修改截止时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 220px"
          />
        </el-form-item>
      </el-form>
      <el-alert
        type="info" :closable="false"
        title="烤盘数或交付截止变化后，已保存的排产版本会标记为已过期，需到「排产」页基于最新数据重算。"
      />
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.bar h2 { margin: 0; font-size: 18px; }
.board { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; align-items: start; }
.col { background: #fff; border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
.col-head {
  padding: 10px 14px; font-weight: 700; color: #fff; display: flex; align-items: center; justify-content: space-between;
}
.col-待烤 { background: #9a8f7e; }
.col-烘焙中 { background: #E08600; }
.col-已出炉 { background: #B36B00; }
.col-已质检 { background: #6f9e3a; }
.cnt { background: rgba(255,255,255,0.25); border-radius: 10px; padding: 0 8px; font-size: 12px; }
.col-body { padding: 10px; min-height: 120px; display: flex; flex-direction: column; gap: 10px; }
.card { border: 1px solid var(--line); border-radius: 10px; padding: 10px; background: #fffdf8; box-shadow: 0 1px 4px rgba(179,107,0,0.08); }
.card-broken { border-color: #e3a3a3; background: #fff7f5; }
.alert-line { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.risk-tip { font-size: 12px; line-height: 1.5; margin-top: 6px; padding: 4px 6px; border-radius: 6px; }
.risk-high { color: #b4443e; background: #fbe7e6; }
.risk-mid { color: #9a6a12; background: #faf0d7; }
.risk-high-text { color: #b4443e; font-weight: 700; }
.risk-mid-text { color: #9a6a12; font-weight: 700; }
.card-top { display: flex; justify-content: space-between; align-items: center; }
.badge { font-size: 12px; color: var(--muted); }
.oven { font-weight: 700; color: var(--primary-deep); }
.recipe { font-size: 15px; font-weight: 600; margin: 4px 0; }
.meta { font-size: 12px; color: var(--muted); }
.acts { margin-top: 8px; }
.done-tip { font-size: 12px; color: #6f9e3a; }
.empty { color: var(--muted); font-size: 13px; text-align: center; padding: 18px 0; }
.hint { font-size: 12px; color: var(--muted); margin-left: 8px; }
</style>
