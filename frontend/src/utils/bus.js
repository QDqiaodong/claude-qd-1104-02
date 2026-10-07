// 跨视图事件：工单看板触发“故障接管” → App 层全局弹窗；接管成功后各视图刷新
import { reactive } from 'vue'

const listeners = { 'takeover:open': [], 'takeover:taken': [] }

function on(event, fn) {
  listeners[event]?.push(fn)
  return () => off(event, fn)
}
function off(event, fn) {
  listeners[event] = (listeners[event] || []).filter((f) => f !== fn)
}
function emit(event, payload) {
  ;(listeners[event] || []).forEach((fn) => fn(payload))
}

// 全局接管弹窗状态（App.vue 持有实例，其它视图只发事件）
export const takeoverState = reactive({ open: false, sourceOvenId: null })

export function openTakeover(sourceOvenId) {
  takeoverState.sourceOvenId = sourceOvenId
  takeoverState.open = true
}

export default { on, off, emit, openTakeover }
