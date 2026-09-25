<template>
  <div>
    <div class="cd fi" style="padding:0">
      <table class="tb">
        <thead>
          <tr><th>时间</th><th>用户</th><th>操作</th><th>详情</th><th>IP</th></tr>
        </thead>
        <tbody>
          <tr v-for="(log, i) in logs" :key="i">
            <td style="color:var(--ink3)">{{ log.time }}</td>
            <td>{{ log.user }}</td>
            <td><span class="badge" :class="logBadge(log.type)">{{ log.type }}</span></td>
            <td>{{ log.detail }}</td>
            <td style="color:var(--ink3)">{{ log.ip }}</td>
          </tr>
          <tr v-if="!logs.length"><td colspan="5" class="empty-state">暂无日志</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { getAuditLogs } from '../api/index'
import gsap from 'gsap'

const logs = ref([])

function logBadge(type) {
  if (type === '登录') return 'badge-m'
  if (type === '导出') return 'badge-i'
  if (type === '创建用户') return 'badge-e'
  return 'badge-fl'
}

onMounted(async () => {
  try {
    const res = await getAuditLogs()
    if (res.code === 200) logs.value = res.data || []
  } catch (e) {
    // 接口未实现时展示空表
    console.warn('日志接口暂不可用')
  }
})

let ctx
onMounted(() => {
  ctx = gsap.context(() => {
    gsap.fromTo('.fi', { y: 10, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.35, ease: 'power2.out', clearProps: 'transform' })
  })
})
onUnmounted(() => { ctx && ctx.revert() })
</script>
