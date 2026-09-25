<template>
  <div class="kpi-card" :class="colorClass">
    <div class="kpi-header">
      <div class="kpi-icon">
        <component :is="icon" :size="24" />
      </div>
      <div class="kpi-badge" :class="trend > 0 ? 'up' : 'down'">
        {{ trend > 0 ? '+' : '' }}{{ trend }}%
      </div>
    </div>
    <div class="kpi-value">{{ value }}</div>
    <div class="kpi-label">{{ label }}</div>
    <div class="kpi-trend">
      <TrendingUp v-if="trend > 0" :size="16" />
      <TrendingDown v-else :size="16" />
      <span>{{ trend > 0 ? '上涨' : '下跌' }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { TrendingUp, TrendingDown } from 'lucide-vue-next'

const props = defineProps({
  value: {
    type: String,
    required: true
  },
  label: {
    type: String,
    required: true
  },
  trend: {
    type: Number,
    default: 0
  },
  color: {
    type: String,
    default: 'blue',
    validator: (value) => ['blue', 'green', 'amber', 'red', 'purple'].includes(value)
  },
  icon: {
    type: Object,
    required: true
  }
})

const colorClass = computed(() => `kpi-card-${props.color}`)
</script>

<style scoped>
.kpi-card {
  position: relative;
  padding: 1.5rem;
  background-color: var(--card-color);
  border-radius: 16px;
  border: 1px solid var(--border-color);
  overflow: hidden;
  transition: all 0.3s ease;
}

.kpi-card::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 16px;
  padding: 2px;
  background: linear-gradient(135deg, var(--gradient-start), var(--gradient-end));
  -webkit-mask: 
    linear-gradient(#fff 0 0) content-box, 
    linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  opacity: 0;
  transition: opacity 0.3s ease;
}

.kpi-card:hover::before {
  opacity: 1;
}

.kpi-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.2);
}

/* 颜色变体 */
.kpi-card-blue {
  --gradient-start: #3b82f6;
  --gradient-end: #2563eb;
}

.kpi-card-green {
  --gradient-start: #10b981;
  --gradient-end: #059669;
}

.kpi-card-amber {
  --gradient-start: #f59e0b;
  --gradient-end: #d97706;
}

.kpi-card-red {
  --gradient-start: #ef4444;
  --gradient-end: #dc2626;
}

.kpi-card-purple {
  --gradient-start: #8b5cf6;
  --gradient-end: #7c3aed;
}

.kpi-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1rem;
}

.kpi-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(var(--gradient-start-rgb), 0.1), rgba(var(--gradient-end-rgb), 0.05));
  color: var(--gradient-start);
}

.kpi-badge {
  font-size: 0.75rem;
  font-weight: 600;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
}

.kpi-badge.up {
  background-color: rgba(16, 185, 129, 0.1);
  color: #10b981;
}

.kpi-badge.down {
  background-color: rgba(239, 68, 68, 0.1);
  color: #ef4444;
}

.kpi-value {
  font-size: 2rem;
  font-weight: 700;
  color: var(--text-color);
  margin-bottom: 0.5rem;
}

.kpi-label {
  font-size: 0.9rem;
  color: var(--text-secondary);
  margin-bottom: 0.75rem;
}

.kpi-trend {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.kpi-trend svg {
  color: var(--gradient-start);
}
</style>