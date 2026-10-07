<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import api from '../api'
import { ElMessage } from 'element-plus'

const list = ref([])
const orders = ref([])
const dialog = ref(false)
const editing = ref(false)
const form = reactive({ id: null, bakeOrderId: null, qty: 1, channel: '门店' })

const inspectedOrders = computed(() => orders.value.filter((o) => o.status === '已质检'))

async function load() {
  const [sh, od] = await Promise.all([api.listShipments(), api.listBakeOrders()])
  list.value = sh
  orders.value = od
}

function openCreate() {
  editing.value = false
  Object.assign(form, { id: null, bakeOrderId: null, qty: 1, channel: '门店' })
  dialog.value = true
}

function openEdit(row) {
  editing.value = true
  Object.assign(form, { id: row.id, bakeOrderId: row.bakeOrderId, qty: row.qty, channel: row.channel })
  dialog.value = true
}

async function submit() {
  try {
    if (editing.value) {
      await api.updateShipment(form.id, { qty: form.qty, channel: form.channel })
    } else {
      await api.createShipment({ bakeOrderId: form.bakeOrderId, qty: form.qty, channel: form.channel })
    }
    dialog.value = false
    await load(); emitChanged()
    ElMessage.success('已保存')
  } catch (e) { ElMessage.error(e.message) }
}

async function doSend(row) {
  try { await api.sendShipment(row.id); await load(); emitChanged(); ElMessage.success('已发货') }
  catch (e) { ElMessage.error(e.message) }
}

const emit = defineEmits(['changed'])
function emitChanged() { emit('changed') }

onMounted(load)
</script>

<template>
  <div>
    <div class="bar">
      <h2>出货管理</h2>
      <el-button type="primary" @click="openCreate">新增出货</el-button>
    </div>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="bakeOrderId" label="工单ID" width="100" />
      <el-table-column prop="qty" label="数量" width="100" />
      <el-table-column label="渠道" width="100">
        <template #default="{ row }"><el-tag :type="row.channel === '门店' ? 'success' : 'warning'">{{ row.channel }}</el-tag></template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }"><el-tag :type="row.status === '已发' ? 'success' : 'info'">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" min-width="180">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="success" v-if="row.status === '待发'" @click="doSend(row)">发货</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="editing ? '编辑出货' : '新增出货'" width="420px">
      <el-form label-width="100px">
        <el-form-item label="工单">
          <el-select v-model="form.bakeOrderId" placeholder="仅已质检工单" style="width: 220px" :disabled="editing">
            <el-option v-for="o in inspectedOrders" :key="o.id" :label="'工单 #' + o.id" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="form.qty" :min="1" :max="9999" />
        </el-form-item>
        <el-form-item label="渠道">
          <el-select v-model="form.channel" style="width: 160px">
            <el-option label="门店" value="门店" />
            <el-option label="外卖" value="外卖" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.bar h2 { margin: 0; font-size: 18px; }
</style>
