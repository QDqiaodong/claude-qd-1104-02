import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import OvenView from '../views/OvenView.vue'
import BreadRecipeView from '../views/BreadRecipeView.vue'
import BakeOrderView from '../views/BakeOrderView.vue'
import ScheduleView from '../views/ScheduleView.vue'
import ShipmentView from '../views/ShipmentView.vue'

const routes = [
  { path: '/', redirect: '/oven' },
  { path: '/oven', name: 'oven', component: OvenView },
  { path: '/bread-recipe', name: 'bread-recipe', component: BreadRecipeView },
  { path: '/bake-order', name: 'bake-order', component: BakeOrderView },
  { path: '/schedule', name: 'schedule', component: ScheduleView },
  { path: '/shipment', name: 'shipment', component: ShipmentView },
  { path: '/:pathMatch(.*)*', redirect: '/oven' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
