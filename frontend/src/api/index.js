import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const params = (o) => ({ params: o })

export default {
  // ---------------- 烤箱 ----------------
  listOvens: (status) => call(http.get('/ovens', params(status ? { status } : {}))),
  createOven: (d) => call(http.post('/ovens', d)),
  updateOven: (id, d) => call(http.put(`/ovens/${id}`, d)),
  breakOven: (id) => call(http.post(`/ovens/${id}/break`)),
  repairOven: (id) => call(http.post(`/ovens/${id}/repair`)),

  // ---------------- 面包配方 ----------------
  listBreadRecipes: (status) => call(http.get('/bread-recipes', params(status ? { status } : {}))),
  createBreadRecipe: (d) => call(http.post('/bread-recipes', d)),
  updateBreadRecipe: (id, d) => call(http.put(`/bread-recipes/${id}`, d)),

  // ---------------- 烘焙工单 ----------------
  listBakeOrders: (status) => call(http.get('/bake-orders', params(status ? { status } : {}))),
  createBakeOrder: (d) => call(http.post('/bake-orders', d)),
  updateBakeOrder: (id, d) => call(http.put(`/bake-orders/${id}`, d)),
  startBakeOrder: (id) => call(http.post(`/bake-orders/${id}/start`)),
  doneBakeOrder: (id) => call(http.post(`/bake-orders/${id}/done`)),
  inspectBakeOrder: (id) => call(http.post(`/bake-orders/${id}/inspect`)),

  // ---------------- 故障接管 ----------------
  takeoverPreview: (sourceOvenId) => call(http.get(`/takeovers/ovens/${sourceOvenId}/preview`)),
  takeover: (sourceOvenId, items) =>
    call(http.post(`/takeovers/ovens/${sourceOvenId}/take`, { items })),

  // ---------------- 生产排产 ----------------
  scheduleCurrent: () => call(http.get('/schedule/current')),
  schedulePreview: (priorities) => call(http.post('/schedule/preview', { priorities })),
  saveScheduleVersion: (d) => call(http.post('/schedule/versions', d)),

  // ---------------- 出货 ----------------
  listShipments: (status) => call(http.get('/shipments', params(status ? { status } : {}))),
  createShipment: (d) => call(http.post('/shipments', d)),
  updateShipment: (id, d) => call(http.put(`/shipments/${id}`, d)),
  sendShipment: (id) => call(http.post(`/shipments/${id}/send`))
}
