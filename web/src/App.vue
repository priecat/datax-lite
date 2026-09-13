<template>
  <router-view />
</template>

<style>
html, body, #app {
  height: 100%;
  margin: 0;
  background: #f5f7fa;
}

/* 列表页通用适配：卡片占满视口剩余高度，表格在自己区域内滚动（固定表头），工具栏/分页条不滚出视野 */
.fill-card > .el-card__body {
  display: flex;
  flex-direction: column;
  /* 顶栏 56px + el-main 上下内边距 32px + 卡片内边距 40px + 卡片边框 2px */
  height: calc(100vh - 130px);
}
.fill-card .toolbar { flex-shrink: 0; }
.fill-card .table-wrap { flex: 1; min-height: 0; }
.fill-card .el-pagination { flex-shrink: 0; }

/* 卡片式页面（如插件管理）：卡片占满视口，内容在卡片内滚动 */
.fill-scroll > .el-card__body {
  height: calc(100vh - 130px);
  overflow-y: auto;
}
</style>
