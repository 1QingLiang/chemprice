<template>
  <!-- 使用手册：内嵌 /guide/ 静态手册页（?embed=1 会让手册隐藏自带的品牌栏）。
       用 iframe 而不是把 19 章内容搬成 Vue 组件：手册本身是独立静态页，
       改它只需 sudo cp，不必每次重新 build 前端；也避免同一份内容两处维护。 -->
  <div class="help-embed">
    <iframe
      class="help-frame"
      src="/guide/index.html?embed=1"
      title="ChemPrice 使用手册"
      loading="eager"
    ></iframe>
  </div>
</template>

<script setup>
</script>

<style scoped>
.help-embed { height: 100%; min-height: 0; display: flex; }
.help-frame { flex: 1; width: 100%; height: 100%; border: 0; display: block; background: #fff; }
</style>
