<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import OvenView from './views/OvenView.vue'
import BreadRecipeView from './views/BreadRecipeView.vue'
import BakeOrderView from './views/BakeOrderView.vue'
import ShipmentView from './views/ShipmentView.vue'
import TakeoverDialog from './components/TakeoverDialog.vue'
import bus, { takeoverState } from './utils/bus'

// ------------------------------------------------------------------
// 导航形态：图标窄侧栏（左侧窄竖栏，4 个模块各一个 icon 按钮）
// ------------------------------------------------------------------

const modules = [
  {
    path: '/oven', label: '烤箱', comp: OvenView,
    icon: '<rect x="3" y="6" width="18" height="13" rx="2" fill="none" stroke="currentColor" stroke-width="1.6"/><line x1="3" y1="10.5" x2="21" y2="10.5" stroke="currentColor" stroke-width="1.6"/><circle cx="8" cy="14.5" r="1.3" fill="currentColor"/><circle cx="13" cy="14.5" r="1.3" fill="currentColor"/>'
  },
  {
    path: '/bread-recipe', label: '配方', comp: BreadRecipeView,
    icon: '<path d="M5 4h11a2 2 0 0 1 2 2v14H7a2 2 0 0 1-2-2z" fill="none" stroke="currentColor" stroke-width="1.6"/><line x1="8" y1="9" x2="14" y2="9" stroke="currentColor" stroke-width="1.4"/><line x1="8" y1="13" x2="14" y2="13" stroke="currentColor" stroke-width="1.4"/>'
  },
  {
    path: '/bake-order', label: '工单', comp: BakeOrderView,
    icon: '<rect x="5" y="4" width="14" height="16" rx="2" fill="none" stroke="currentColor" stroke-width="1.6"/><line x1="8" y1="9" x2="16" y2="9" stroke="currentColor" stroke-width="1.4"/><line x1="8" y1="13" x2="16" y2="13" stroke="currentColor" stroke-width="1.4"/><line x1="8" y1="17" x2="13" y2="17" stroke="currentColor" stroke-width="1.4"/>'
  },
  {
    path: '/shipment', label: '出货', comp: ShipmentView,
    icon: '<path d="M3 7l9-4 9 4v10l-9 4-9-4z" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M3 7l9 4 9-4M12 11v10" fill="none" stroke="currentColor" stroke-width="1.4"/>'
  }
]

const currentPath = ref(router.currentRoute.value.path)
const counts = ref({})
// 接管成功后自增，强制当前视图重挂载（重新拉取归属/状态，保证刷新后一致）
const viewKey = ref(0)

const currentView = computed(() => {
  const m = modules.find((mm) => mm.path === currentPath.value)
  return m ? m.comp : OvenView
})

function onTaken() {
  viewKey.value += 1
  loadCounts()
}

onMounted(() => {
  bus.on('takeover:taken', onTaken)
  currentPath.value = router.currentRoute.value.path
  loadCounts()
})
onUnmounted(() => bus.off('takeover:taken', onTaken))

async function loadCounts() {
  try {
    const [ov, rp, od, sh] = await Promise.all([
      api.listOvens(), api.listBreadRecipes(), api.listBakeOrders(), api.listShipments()
    ])
    counts.value = {
      '/oven': ov.length,
      '/bread-recipe': rp.length,
      '/bake-order': od.length,
      '/shipment': sh.length
    }
  } catch (e) { /* 计数失败不影响主流程 */ }
}

function go(path) {
  router.push(path)
}

watch(() => router.currentRoute.value.path, (p) => { currentPath.value = p })
</script>

<template>
  <div class="app-shell">
    <!-- 图标窄侧栏 -->
    <nav class="sidebar">
      <div class="brand-mark">焙</div>
      <button
        v-for="m in modules"
        :key="m.path"
        class="nav-btn"
        :class="{ on: currentPath === m.path }"
        @click="go(m.path)"
        :title="m.label"
      >
        <svg viewBox="0 0 24 24" v-html="m.icon"></svg>
        <span class="nav-label">{{ m.label }}</span>
        <span class="nav-count" v-if="counts[m.path] !== undefined">{{ counts[m.path] }}</span>
      </button>
    </nav>

    <!-- 主内容区 -->
    <main class="content">
      <header class="topbar">
        <div class="brand-text">
          <div class="brand-title">烘焙坊 · 烤箱设备与面包批次</div>
          <div class="brand-sub">图标窄侧栏导航 · 琥珀主题 · 看板分列生产</div>
        </div>
      </header>
      <section class="panel">
        <component :is="currentView" :key="currentPath + '-' + viewKey" @changed="loadCounts" />
      </section>
    </main>

    <!-- 全局故障接管弹窗：烤箱列表与工单看板均可触发 -->
    <TakeoverDialog
      v-model="takeoverState.open"
      :source-oven-id="takeoverState.sourceOvenId"
      @taken="bus.emit('takeover:taken')"
    />
  </div>
</template>

<style scoped>
.app-shell { display: flex; min-height: 100vh; }

/* 窄侧栏 */
.sidebar {
  width: 78px; flex-shrink: 0;
  background: linear-gradient(180deg, var(--primary-deep) 0%, var(--primary) 100%);
  display: flex; flex-direction: column; align-items: center; gap: 10px;
  padding: 14px 0; color: #fff;
}
.brand-mark {
  width: 42px; height: 42px; border-radius: 12px; margin-bottom: 8px;
  background: #fff; color: var(--primary-deep);
  display: flex; align-items: center; justify-content: center;
  font-size: 20px; font-weight: 800;
}
.nav-btn {
  position: relative; width: 60px; height: 60px; border: none; border-radius: 12px;
  background: transparent; color: #fff; cursor: pointer;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px;
  transition: all 0.16s ease; opacity: 0.82;
}
.nav-btn svg { width: 24px; height: 24px; }
.nav-btn .nav-label { font-size: 12px; }
.nav-btn:hover { background: rgba(255, 255, 255, 0.16); opacity: 1; }
.nav-btn.on { background: #fff; color: var(--primary-deep); opacity: 1; box-shadow: 0 4px 12px rgba(179, 107, 0, 0.3); }
.nav-count {
  position: absolute; top: 4px; right: 6px; min-width: 18px; height: 18px; padding: 0 4px;
  border-radius: 9px; background: var(--primary-deep); color: #fff; font-size: 11px;
  display: flex; align-items: center; justify-content: center;
}
.nav-btn.on .nav-count { background: var(--primary); }

/* 主区 */
.content { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.topbar {
  height: 60px; display: flex; align-items: center; padding: 0 22px;
  border-bottom: 1px solid var(--line); background: #fff;
}
.brand-title { font-size: 17px; font-weight: 700; }
.brand-sub { font-size: 12px; color: var(--muted); margin-top: 2px; }
.panel { flex: 1; padding: 18px 22px; overflow: auto; }
</style>
