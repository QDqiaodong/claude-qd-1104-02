<script setup>
import { onMounted, reactive, ref } from 'vue'
import api from '../api'
import { ElMessage } from 'element-plus'

const list = ref([])
const dialog = ref(false)
const editing = ref(false)
const form = reactive({ id: null, name: '', needTrays: 1, bakeMinutes: 10, status: '上架' })

async function load() {
  list.value = await api.listBreadRecipes()
}

function openCreate() {
  editing.value = false
  Object.assign(form, { id: null, name: '', needTrays: 1, bakeMinutes: 10, status: '上架' })
  dialog.value = true
}

function openEdit(row) {
  editing.value = true
  Object.assign(form, { id: row.id, name: row.name, needTrays: row.needTrays, bakeMinutes: row.bakeMinutes, status: row.status })
  dialog.value = true
}

async function submit() {
  try {
    if (editing.value) {
      await api.updateBreadRecipe(form.id, { name: form.name, needTrays: form.needTrays, bakeMinutes: form.bakeMinutes, status: form.status })
    } else {
      await api.createBreadRecipe({ name: form.name, needTrays: form.needTrays, bakeMinutes: form.bakeMinutes, status: form.status })
    }
    dialog.value = false
    await load()
    emitChanged()
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

const emit = defineEmits(['changed'])
function emitChanged() { emit('changed') }

onMounted(load)
</script>

<template>
  <div>
    <div class="bar">
      <h2>面包配方</h2>
      <el-button type="primary" @click="openCreate">新增配方</el-button>
    </div>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="面包名" min-width="120" />
      <el-table-column prop="needTrays" label="需用烤盘" width="110" />
      <el-table-column prop="bakeMinutes" label="烘焙时长(分)" width="120" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }"><el-tag :type="row.status === '上架' ? 'success' : 'info'">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }"><el-button size="small" @click="openEdit(row)">编辑</el-button></template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="editing ? '编辑配方' : '新增配方'" width="420px">
      <el-form label-width="110px">
        <el-form-item label="面包名">
          <el-input v-model="form.name" placeholder="如 法棍" />
        </el-form-item>
        <el-form-item label="需用烤盘">
          <el-input-number v-model="form.needTrays" :min="1" :max="99" />
        </el-form-item>
        <el-form-item label="烘焙时长(分)">
          <el-input-number v-model="form.bakeMinutes" :min="1" :max="600" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 160px">
            <el-option label="上架" value="上架" />
            <el-option label="隐藏" value="隐藏" />
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
