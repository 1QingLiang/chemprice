<template>
  <div class="gradient-tabs">
    <div class="tabs-container">
      <button 
        v-for="tab in tabs" 
        :key="tab.id"
        class="tab-item"
        :class="{ active: modelValue === tab.id }"
        @click="$emit('update:modelValue', tab.id)"
      >
        <span class="tab-text">{{ tab.name }}</span>
        <div class="tab-indicator"></div>
      </button>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  tabs: {
    type: Array,
    required: true
  },
  modelValue: {
    type: [String, Number],
    required: true
  }
})

const emit = defineEmits(['update:modelValue'])
</script>

<style scoped>
.gradient-tabs {
  width: 100%;
}

.tabs-container {
  display: flex;
  gap: 0.5rem;
  background-color: rgba(255, 255, 255, 0.03);
  padding: 0.25rem;
  border-radius: 12px;
}

.tab-item {
  position: relative;
  flex: 1;
  padding: 0.75rem 1.5rem;
  background: none;
  border: none;
  color: var(--text-secondary);
  font-size: 0.9rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s ease;
  border-radius: 8px;
}

.tab-item:hover {
  color: var(--text-color);
  background-color: rgba(255, 255, 255, 0.05);
}

.tab-item.active {
  color: white;
  background: linear-gradient(135deg, var(--primary-color), #2563eb);
}

.tab-indicator {
  position: absolute;
  bottom: 0;
  left: 10%;
  right: 10%;
  height: 3px;
  background: linear-gradient(90deg, #3b82f6, #10b981);
  border-radius: 3px 3px 0 0;
  opacity: 0;
  transform: scaleX(0);
  transition: all 0.3s ease;
}

.tab-item.active .tab-indicator {
  opacity: 1;
  transform: scaleX(1);
}
</style>