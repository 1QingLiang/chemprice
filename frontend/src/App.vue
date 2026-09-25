<template>
  <router-view />
</template>

<script setup>
import { RouterView } from 'vue-router'
</script>

<style>
:root {
  --primary-color: #3b82f6;
  --secondary-color: #10b981;
  --background-color: #0f172a;
  --sidebar-color: #1e293b;
  --card-color: #1e293b;
  --text-color: #f8fafc;
  --text-secondary: #94a3b8;
  --border-color: #334155;
  --glass-background: rgba(30, 41, 59, 0.8);
}

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background-color: var(--background-color);
  color: var(--text-color);
  line-height: 1.6;
}

#app {
  min-height: 100vh;
}
</style>