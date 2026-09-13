<template>
  <el-card :class="{ 'edit-card-fill': step === 1 || step === 3 }">
    <div class="toolbar">
      <span class="title">{{ isEdit ? '编辑传输任务' : '新建传输任务' }}</span>
      <el-button icon="Back" @click="$router.push('/tasks')">返回</el-button>
    </div>

    <el-steps :active="step" finish-status="success" align-center style="margin-bottom: 24px">
      <el-step title="选择数据源" description="源与目标连接、数据库" />
      <el-step title="选择同步对象" description="勾选表、字段映射" />
      <el-step title="高级选项" description="写入模式、并发等" />
      <el-step title="任务详情" description="执行计划与配置预览" />
    </el-steps>

    <!-- Step 1 -->
    <div v-show="step === 0">
      <el-form :model="form" label-width="110px" style="max-width: 760px">
        <el-form-item label="任务名称" required>
          <el-input v-model="form.name" placeholder="" />
        </el-form-item>
        <el-divider content-position="left">源</el-divider>
        <el-form-item label="源数据源" required>
          <el-select v-model="form.sourceDatasourceId" style="width: 100%" @change="form.sourceDatabase = ''">
            <el-option v-for="d in sources" :key="d.id" :label="d.name + ' (' + d.host + ')'" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="源数据库" required>
          <el-select v-model="form.sourceDatabase" style="width: 100%" :loading="loadingDbs" @focus="loadDbs">
            <el-option v-for="d in sourceDbs" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-divider content-position="left">目标</el-divider>
        <el-form-item label="目标数据源" required>
          <el-select v-model="form.targetDatasourceId" style="width: 100%" @change="form.targetDatabase = ''">
            <el-option v-for="d in sources" :key="d.id" :label="d.name + ' (' + d.host + ')'" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标数据库" required>
          <el-select v-model="form.targetDatabase" style="width: 100%" :loading="loadingDbs" @focus="loadDbs">
            <el-option v-for="d in dbsOf(form.targetDatasourceId)" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <div class="step-actions">
        <el-button type="primary" @click="toStep2">下一步</el-button>
      </div>
    </div>

    <!-- Step 2 -->
    <div v-show="step === 1" class="step-pane-fill">
      <div class="table-tools">
        <el-button size="small" @click="selectAll(true)">全选</el-button>
        <el-button size="small" @click="selectAll(false)">全不选</el-button>
        <span style="margin-left: 12px; color: #909399; font-size: 13px">
          已选 {{ selectedCount }} / {{ tables.length }} 张表；未配置字段映射的表默认同步全部字段；目标表不存在时按“自动建表”处理；勾选“重建表”每次执行前删除并重建；未勾选时目标表缺少字段将自动补齐(ALTER ADD COLUMN)
        </span>
      </div>
      <div class="table-wrap">
        <el-table :data="tables" border size="small" row-key="name" height="100%">
        <el-table-column width="50">
          <template #default="{ row }">
            <el-checkbox v-model="row.enabled" />
          </template>
        </el-table-column>
        <el-table-column prop="name" label="源表" min-width="140" />
        <el-table-column label="目标表名" min-width="140">
          <template #default="{ row }">
            <el-input v-model="row.targetTable" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="rows" label="行数(约)" width="100" />
        <el-table-column prop="comment" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column label="选项" width="240">
          <template #default="{ row }">
            <el-checkbox v-model="row.autoCreateTable" size="small">自动建表</el-checkbox>
            <el-checkbox v-model="row.truncateBefore" size="small">清空目标</el-checkbox>
            <el-checkbox v-model="row.recreateTable" size="small">重建表</el-checkbox>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" @click="openMapping(row)">字段映射</el-button>
          </template>
        </el-table-column>
        </el-table>
      </div>
      <div class="step-actions">
        <el-button @click="step = 0">上一步</el-button>
        <el-button type="primary" @click="toStep3">下一步</el-button>
      </div>
    </div>

    <!-- Step 3 -->
    <div v-show="step === 2">
      <el-form :model="options" label-width="150px" style="max-width: 640px">
        <el-form-item label="写入模式">
          <div class="write-mode-row">
            <el-select v-model="options.writeMode">
              <el-option label="insert（插入）" value="insert" />
              <el-option label="insert ignore（忽略冲突）" value="insert ignore" />
              <el-option label="replace（覆盖写入）" value="replace" />
              <el-option label="update（按主键更新）" value="update" />
            </el-select>
            <el-tooltip placement="top" effect="light">
              <template #content>
                <div class="write-mode-help">
                  <p><b>insert（插入）</b>：直接 INSERT INTO 写入。主键/唯一键冲突时报错，冲突记录计入脏数据（受"允许错误条数/比例"控制）。</p>
                  <p><b>insert ignore（忽略冲突）</b>：INSERT IGNORE INTO 写入。主键/唯一键冲突时跳过该行，继续写入后续数据，不报错。</p>
                  <p><b>replace（覆盖写入）</b>：REPLACE INTO 写入。冲突时先删除旧行再插入新行（整行替换）；目标表中未同步的列会被重置为默认值。</p>
                  <p><b>update（按主键更新）</b>：INSERT ... ON DUPLICATE KEY UPDATE。冲突时按主键更新除主键外的已同步列；未同步的列保留原值。</p>
                </div>
              </template>
              <el-icon class="help-icon"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
        </el-form-item>
        <el-form-item label="写入批次大小">
          <div class="write-mode-row">
            <el-input-number v-model="options.batchSize" :min="16" :max="65535" :step="256" />
            <el-tooltip placement="top" effect="light">
              <template #content>
                <div class="write-mode-help">
                  <p>写入端攒批的记录数。mysqlwriter 逐条接收 reader 发来的 Record，攒满 batchSize 条后通过 JDBC <b>executeBatch()</b> 一次性提交目标库，任务结束时不足一批的也会补交。</p>
                  <p>批越大，网络往返和事务提交次数越少、吞吐越高，但单批占用内存也越大；批量提交失败时会自动降级为逐条写入，以精确定位脏数据。</p>
                </div>
              </template>
              <el-icon class="help-icon"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
        </el-form-item>
        <el-form-item label="并发通道数">
          <div class="write-mode-row">
            <el-input-number v-model="options.channel" :min="1" :max="32" />
            <el-tooltip placement="top" effect="light">
              <template #content>
                <div class="write-mode-help">
                  <p>写入 job.setting.speed.channel，即并发运行的 reader+writer 任务对数。框架按通道数把任务切分并均分到若干 TaskGroup 并发执行，通道越多吞吐越高，但对源库/目标库的连接数和压力也成倍增加。</p>
                  <p>注意：单表读取只有配置了 splitPk（切分键）才会被切成多片真正并行；未配置时，多通道仅对"多张表"任务有效。</p>
                </div>
              </template>
              <el-icon class="help-icon"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
        </el-form-item>
        <el-form-item label="错误限制方式">
          <el-radio-group v-model="options.errorLimitMode">
            <el-radio value="record">按条数</el-radio>
            <el-radio value="percentage">按比例</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="options.errorLimitMode === 'record' ? '允许错误条数' : '允许错误比例(0-1)'">
          <div class="write-mode-row">
            <el-input-number v-if="options.errorLimitMode === 'record'" v-model="options.errorLimitRecord" :min="0" />
            <el-input-number v-else v-model="options.errorLimitPercentage" :min="0" :max="1" :step="0.01" />
            <el-tooltip v-if="options.errorLimitMode === 'record'" placement="top" effect="light">
              <template #content>
                <div class="write-mode-help">
                  <p>写入 job.setting.errorLimit.record。调度器按 core.container.job.sleepInterval 轮询脏数据统计，累计条数<b>超过
                  </b>该值时任务失败终止；设为 0 表示发生任何脏数据后终止任务。</p>
                  <p>注意：终止只是"不再继续写"，此前已提交的数据不会回滚；检查有一轮采样延迟，脏数据会先积累一小批才终止。</p>
                </div>
              </template>
              <el-icon class="help-icon"><QuestionFilled /></el-icon>
            </el-tooltip>
            <el-tooltip v-else placement="top" effect="light">
              <template #content>
                <div class="write-mode-help">
                  <p>写入 job.setting.errorLimit.percentage，取值 [0,1]。任务结束时按 脏数据条数 / 总读取条数 计算，超过该值则任务判为失败。</p>
                  <p>此模式下 job.json 不再写入条数限制。</p>
                </div>
              </template>
              <el-icon class="help-icon"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
        </el-form-item>
      </el-form>

      <!-- 任务完成通知 -->
      <el-divider content-position="left">任务完成通知</el-divider>
      <div class="notify-tip">
        任务执行结束后（成功/失败/停止均触发）将按顺序向以下接口发送 HTTP 请求。
        支持占位符：${taskId} ${taskName} ${logId} ${state}(SUCCESS/FAILED/STOPPED) ${message}
        ${triggerType}(manual/schedule) ${startTime} ${endTime} ${durationMs}
        ${readRecords} ${writeRecords} ${errorRecords} ${readBytes} ${speedRecord} ${speedByte}
      </div>
      <div v-for="(n, ni) in notifies" :key="ni" class="notify-card">
        <div class="notify-head">
          <el-select v-model="n.method" style="width: 110px">
            <el-option label="GET" value="GET" />
            <el-option label="POST" value="POST" />
            <el-option label="PUT" value="PUT" />
            <el-option label="DELETE" value="DELETE" />
          </el-select>
          <el-input v-model="n.url" placeholder="http://example.com/notify" class="notify-url" />
          <el-button type="danger" icon="Delete" plain @click="notifies.splice(ni, 1)" />
        </div>
        <div class="notify-body">
          <div class="notify-col">
            <div class="kv-label">Headers</div>
            <div v-for="(h, hi) in n.headers" :key="hi" class="kv-row">
              <el-input v-model="h.key" placeholder="Header 名称" size="small" />
              <el-input v-model="h.value" placeholder="值" size="small" />
              <el-button icon="Delete" size="small" text @click="n.headers.splice(hi, 1)" />
            </div>
            <el-button size="small" text icon="Plus" @click="n.headers.push({ key: '', value: '' })">添加 Header</el-button>
          </div>
          <div class="notify-col">
            <div class="kv-label">Params（拼接到 URL）</div>
            <div v-for="(p, pi) in n.params" :key="pi" class="kv-row">
              <el-input v-model="p.key" placeholder="参数名" size="small" />
              <el-input v-model="p.value" placeholder="值" size="small" />
              <el-button icon="Delete" size="small" text @click="n.params.splice(pi, 1)" />
            </div>
            <el-button size="small" text icon="Plus" @click="n.params.push({ key: '', value: '' })">添加参数</el-button>
          </div>
          <div class="notify-col" v-if="n.method !== 'GET'">
            <div class="kv-label">Body</div>
            <el-radio-group v-model="n.bodyType" size="small" @change="onBodyTypeChange(n)">
              <el-radio-button value="none">无</el-radio-button>
              <el-radio-button value="form">x-www-form-urlencoded</el-radio-button>
              <el-radio-button value="json">JSON</el-radio-button>
              <el-radio-button value="raw">RAW</el-radio-button>
            </el-radio-group>
            <template v-if="n.bodyType === 'form'">
              <div v-for="(f, fi) in n.formFields" :key="fi" class="kv-row" style="margin-top: 8px">
                <el-input v-model="f.key" placeholder="字段名" size="small" />
                <el-input v-model="f.value" placeholder="值" size="small" />
                <el-button icon="Delete" size="small" text @click="n.formFields.splice(fi, 1)" />
              </div>
              <el-button size="small" text icon="Plus" style="margin-top: 8px"
                         @click="n.formFields.push({ key: '', value: '' })">添加字段</el-button>
            </template>
            <el-input v-else-if="n.bodyType === 'json' || n.bodyType === 'raw'"
                      v-model="n.rawBody" type="textarea" :rows="5" style="margin-top: 8px"
                      :placeholder="n.bodyType === 'json'
                        ? '{&quot;task&quot;:&quot;${taskName}&quot;,&quot;state&quot;:&quot;${state}&quot;,&quot;read&quot;:${readRecords}}'
                        : '请求体内容'" />
          </div>
        </div>
      </div>
      <el-button type="primary" plain icon="Plus" @click="addNotify">添加通知</el-button>

      <div class="step-actions">
        <el-button @click="step = 1">上一步</el-button>
        <el-button type="primary" @click="toStep4">下一步</el-button>
      </div>
    </div>

    <!-- Step 4 任务详情：执行计划预览 -->
    <div v-show="step === 3" class="step-pane-fill">
      <div class="plan-scroll">
        <div v-if="planLoading" v-loading="planLoading" style="min-height: 240px"></div>
        <template v-else-if="plan">
          <el-alert v-for="(w, i) in plan.warnings" :key="'gw' + i" type="warning" :title="w"
                    :closable="false" style="margin-bottom: 10px" />

          <!-- 同步方向：源 → 目标，防止方向搞反 -->
          <div class="direction-bar">
            <div class="direction-card source">
              <div class="direction-tag">源 SOURCE（只读）</div>
              <div class="direction-name">{{ plan.source.name }}</div>
              <div class="direction-meta">{{ plan.source.type }} · {{ plan.source.host }}:{{ plan.source.port }}</div>
              <div class="direction-db">数据库：<b>{{ plan.source.database }}</b></div>
            </div>
            <div class="direction-arrow">
              <el-icon :size="34"><Right /></el-icon>
              <span>数据流向</span>
            </div>
            <div class="direction-card target">
              <div class="direction-tag">目标 TARGET（写入）</div>
              <div class="direction-name">{{ plan.target.name }}</div>
              <div class="direction-meta">{{ plan.target.type }} · {{ plan.target.host }}:{{ plan.target.port }}</div>
              <div class="direction-db">数据库：<b>{{ plan.target.database }}</b></div>
            </div>
          </div>

          <el-descriptions :column="3" border size="small" style="margin-bottom: 16px">
            <el-descriptions-item label="任务名称">{{ form.name }}</el-descriptions-item>
            <el-descriptions-item label="同步表数量">{{ plan.tables.length }} 张</el-descriptions-item>
            <el-descriptions-item label="写入模式">{{ options.writeMode }}</el-descriptions-item>
            <el-descriptions-item label="批次大小 / 通道数">{{ options.batchSize }} / {{ options.channel }}</el-descriptions-item>
            <el-descriptions-item label="错误限制">{{ errorLimitText }}</el-descriptions-item>
          </el-descriptions>

          <div class="plan-tables-head">
            <span class="plan-section-title">执行计划</span>
            <el-button size="small" icon="Refresh" :loading="planLoading" @click="loadPlan">刷新预览</el-button>
          </div>
          <pre class="prepare-log">{{ plan.prepareLogs.join('\n') }}</pre>
          <el-collapse v-model="openTables">
            <el-collapse-item v-for="(t, i) in plan.tables" :key="t.sourceTable" :name="i">
              <template #title>
                <span class="plan-tbl-title">
                  {{ t.sourceTable }} → {{ t.targetTable }}
                  <span style="color: #909399; font-size: 12px">（{{ t.selectedColumnCount }} 字段）</span>
                  <el-tag v-if="t.warnings.length" type="warning" size="small" effect="light" style="margin-left: 8px">
                    {{ t.warnings.length }} 项提醒
                  </el-tag>
                </span>
              </template>
              <div class="plan-tbl-body">
                <div class="plan-meta">
                  <el-tag size="small" type="info" effect="plain">
                    切分键: {{ t.splitPk || '-' }}<template v-if="t.splitPkDefault && t.splitPk">（默认主键）</template>
                  </el-tag>
                  <el-tag v-if="t.where" size="small" type="info" effect="plain">WHERE {{ t.where }}</el-tag>
                  <el-tag v-if="t.recreateTable" size="small" type="danger" effect="plain">重建表</el-tag>
                  <el-tag v-if="t.truncateBefore && !t.recreateTable" size="small" type="warning" effect="plain">清空目标</el-tag>
                </div>
                <el-alert v-for="(w, wi) in t.warnings" :key="'w' + wi" type="warning" :title="w"
                          :closable="false" style="margin-bottom: 8px" />
                <el-table :data="t.columns" border size="small" style="margin-top: 8px">
                  <el-table-column prop="source" label="源字段" min-width="130" />
                  <el-table-column prop="sourceType" label="类型" min-width="110" />
                  <el-table-column prop="target" label="目标字段" min-width="130" />
                  <el-table-column label="状态" width="170">
                    <template #default="{ row }">
                      <el-tag v-if="!row.selected" type="info" size="small">不同步</el-tag>
                      <template v-else>
                        <el-tag v-if="row.renamed" type="warning" size="small">改名</el-tag>
                        <el-tag v-if="row.missingInTarget" type="danger" size="small" style="margin-left: 4px">缺失→补齐</el-tag>
                        <span v-if="!row.renamed && !row.missingInTarget" class="plan-col-ok">正常</span>
                      </template>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </el-collapse-item>
          </el-collapse>

          <!-- 任务完成通知（可能多条）：执行计划之下、job.json 之上逐条展示 -->
          <template v-if="plan.notifies && plan.notifies.length">
            <el-divider content-position="left">任务完成通知（{{ plan.notifies.length }} 条，任务结束后按顺序触发）</el-divider>
            <div class="notify-preview">
              <div v-for="(n, ni) in plan.notifies" :key="'nt' + ni" class="notify-preview-item">
                <el-tag size="small" :type="n.method === 'GET' ? 'info' : 'success'">{{ n.method }}</el-tag>
                <span class="notify-preview-url">{{ n.url }}</span>
                <span v-if="n.bodyType && n.bodyType !== 'none'" class="notify-preview-body">body: {{ n.bodyType }}</span>
              </div>
            </div>
          </template>

          <el-divider content-position="left">DataX 作业配置（job.json）</el-divider>
          <div class="jobjson-bar">
            <el-button size="small" icon="CopyDocument" @click="copyJobJson">复制</el-button>
            <span class="jobjson-tip">执行时按此配置运行；执行前还会按上方计划先执行建表/补字段等动作</span>
          </div>
          <pre class="jobjson">{{ prettyJobJson || '（生成失败，请检查上方提醒）' }}</pre>
        </template>
        <el-empty v-else description="预览生成失败，请检查配置或稍后重试">
          <el-button type="primary" @click="loadPlan">重试</el-button>
        </el-empty>
      </div>
      <div class="step-actions">
        <el-button @click="step = 2">上一步</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存任务</el-button>
      </div>
    </div>

    <!-- 字段映射对话框 -->
    <el-dialog v-model="mappingVisible" :title="'字段映射 - ' + (mappingTable ? mappingTable.name : '')" width="95%"
               top="6vh">
      <template v-if="mappingTable">
        <div class="mapping-tools">
          <span>目标表：{{ mappingTable.targetTable }}</span>
          <el-button size="small" @click="loadTargetColumns" :loading="loadingTargetCols">对比目标表字段</el-button>
          <el-tag v-if="targetExists === true" type="success" size="small">目标表已存在</el-tag>
          <el-tag v-else-if="targetExists === false" type="warning" size="small">目标表不存在</el-tag>
        </div>
        <el-table :data="mappingTable.columns" border size="small" max-height="52vh">
          <el-table-column width="50">
            <template #default="{ row }">
              <el-checkbox v-model="row.selected" />
            </template>
          </el-table-column>
          <el-table-column prop="source" label="源字段" min-width="130" />
          <el-table-column prop="sourceType" label="类型" min-width="110" show-overflow-tooltip />
          <el-table-column label="目标字段" min-width="140">
            <template #default="{ row }">
              <el-input v-model="row.target" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="目标类型（上：实际对比 / 下：指定）" min-width="200">
            <template #default="{ row }">
              <div class="cell-type">
                <div class="actual-type" :class="{ 'actual-missing': targetExists === true && !row.targetTypeDb }">
                  {{ row.targetTypeDb || (targetExists === true ? '目标表无此列' : '未对比') }}
                </div>
                <el-select v-model="row.targetType" size="small" filterable allow-create
                           default-first-option clearable placeholder="系统默认"
                           @change="onTargetTypeChange(row)">
                  <el-option v-for="o in targetTypeOptions" :key="o.value" :label="o.label" :value="o.value">
                    <span>{{ o.label }}</span>
                    <span v-if="o.len" class="opt-default-len">{{ o.len }}</span>
                  </el-option>
                </el-select>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="长度" width="92">
            <template #default="{ row }">
              <el-input v-model="row.targetTypeLen" size="small" :disabled="!row.targetType"
                        :placeholder="row.targetType ? '如 10,2' : '-'" />
            </template>
          </el-table-column>
          <el-table-column label="目标类型(最终)" min-width="150">
            <template #default="{ row }">
              <div :class="{ 'type-final-warn': needsAttention(row) }">
                <div class="type-final">{{ finalType(row) || '-' }}</div>
                <div v-if="needsAttention(row)" class="type-final-reason">{{ attentionReason(row) }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="主键" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.pk" size="small">PK</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="selectAllCols(true)">全选字段</el-button>
        <el-button @click="selectAllCols(false)">取消全选</el-button>
        <el-button type="primary" @click="mappingVisible = false">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const step = ref(0)

const sources = ref([])
const sourceDbs = ref([])
const dbsCache = reactive({})
const loadingDbs = ref(false)
const tables = ref([])
const loadingTargetCols = ref(false)
const targetExists = ref(null)
const saving = ref(false)

const form = reactive({
  id: '', name: '', sourceDatasourceId: '', sourceDatabase: '',
  targetDatasourceId: '', targetDatabase: '', description: ''
})
const options = reactive({
  writeMode: 'insert', batchSize: 1024, channel: 3,
  errorLimitMode: 'record', errorLimitRecord: 0, errorLimitPercentage: 0.05
})

// ---------- 任务完成通知 ----------
const notifies = ref([])

function addNotify() {
  notifies.value.push({
    method: 'POST', url: '',
    headers: [], params: [],
    bodyType: 'none', formFields: [], rawBody: ''
  })
}

// body 类型切换时自动维护 Content-Type（json/form 自动注入，可手动修改）
function onBodyTypeChange(n) {
  n.headers = n.headers.filter(h => !(h.auto && (h.key || '').toLowerCase() === 'content-type'))
  if (n.bodyType === 'json' || n.bodyType === 'form') {
    const has = n.headers.some(h => (h.key || '').toLowerCase() === 'content-type')
    if (!has) {
      n.headers.push({
        key: 'Content-Type',
        value: n.bodyType === 'json' ? 'application/json' : 'application/x-www-form-urlencoded',
        auto: true
      })
    }
  }
}

function cleanKv(arr) {
  return (arr || []).filter(x => x.key && x.key.trim()).map(x => ({ key: x.key.trim(), value: x.value || '' }))
}

function buildNotifyConfig() {
  const list = notifies.value
    .filter(n => n.url && n.url.trim())
    .map(n => ({
      method: n.method || 'POST',
      url: n.url.trim(),
      headers: cleanKv(n.headers),
      params: cleanKv(n.params),
      bodyType: n.method === 'GET' ? 'none' : n.bodyType,
      formFields: n.bodyType === 'form' ? cleanKv(n.formFields) : [],
      rawBody: (n.bodyType === 'json' || n.bodyType === 'raw') ? (n.rawBody || '') : ''
    }))
  return list.length ? JSON.stringify(list) : ''
}

function loadNotifyConfig(raw) {
  notifies.value = []
  if (!raw) return
  try {
    notifies.value = (JSON.parse(raw) || []).map(n => ({
      method: n.method || 'POST',
      url: n.url || '',
      headers: (n.headers || []).map(h => ({ key: h.key || '', value: h.value || '' })),
      params: (n.params || []).map(p => ({ key: p.key || '', value: p.value || '' })),
      bodyType: n.bodyType || 'none',
      formFields: (n.formFields || []).map(f => ({ key: f.key || '', value: f.value || '' })),
      rawBody: n.rawBody || ''
    }))
    notifies.value.forEach(onBodyTypeChange)
  } catch (e) { /* ignore */ }
}

const selectedCount = computed(() => tables.value.filter(t => t.enabled).length)

onMounted(async () => {
  sources.value = await http.get('/datasources') || []
  if (isEdit.value) {
    const task = await http.get(`/tasks/${route.params.id}`)
    Object.assign(form, {
      id: task.id, name: task.name,
      sourceDatasourceId: task.sourceDatasourceId, sourceDatabase: task.sourceDatabase,
      targetDatasourceId: task.targetDatasourceId, targetDatabase: task.targetDatabase,
      description: task.description
    })
    let cfg = {}
    try { cfg = JSON.parse(task.config || '{}') } catch (e) { /* ignore */ }
    if (cfg.options) Object.assign(options, cfg.options)
    loadNotifyConfig(task.notifyConfig)
    await enterStep2()
    // 恢复勾选、目标表名、选项与字段映射（以保存的 config 为准）
    for (const saved of (cfg.tables || [])) {
      const t = tables.value.find(x => x.name === saved.sourceTable)
      if (t) {
        t.enabled = saved.enabled
        t.targetTable = saved.targetTable
        t.autoCreateTable = saved.autoCreateTable
        t.truncateBefore = saved.truncateBefore
        t.recreateTable = !!saved.recreateTable || !!saved.recreateIfMismatch
        t.where = saved.where || ''
        t.splitPk = saved.splitPk || ''
        if (saved.columns && saved.columns.length) {
          // enterStep2 重建的表 columns 为 null，直接用保存的字段快照回显
          t.columns = saved.columns.map(c => ({
            source: c.source,
            sourceType: c.sourceType || '',
            target: c.target || c.source,
            selected: c.selected !== false,
            pk: !!c.pk,
            targetType: c.targetType || '',
            targetTypeLen: c.targetTypeLen || '',
            targetTypeDb: ''
          }))
        }
      }
    }
    // 编辑模式直接进入“选择同步对象”步骤展示回显结果
    step.value = 1
  }
})

function dbsOf(dsId) {
  return dbsCache[dsId] || []
}

async function loadDbs() {
  if (form.sourceDatasourceId && !dbsCache[form.sourceDatasourceId]) await fetchDbs(form.sourceDatasourceId)
  if (form.targetDatasourceId && !dbsCache[form.targetDatasourceId]) await fetchDbs(form.targetDatasourceId)
}

async function fetchDbs(dsId) {
  if (!dsId || dbsCache[dsId]) return
  loadingDbs.value = true
  try {
    dbsCache[dsId] = await http.get(`/datasources/${dsId}/databases`) || []
    if (dsId === form.sourceDatasourceId) sourceDbs.value = dbsCache[dsId]
  } finally {
    loadingDbs.value = false
  }
}

async function toStep2() {
  if (!form.name || !form.sourceDatasourceId || !form.sourceDatabase
      || !form.targetDatasourceId || !form.targetDatabase) {
    ElMessage.warning('请完整填写任务名称与源/目标配置')
    return
  }
  await enterStep2()
  step.value = 1
}

// 记录已加载的源(数据源|数据库)，未变化时重复进入第 2 步不重建，避免丢失已勾选/映射状态
let loadedKey = ''

async function enterStep2() {
  const key = form.sourceDatasourceId + '|' + form.sourceDatabase
  if (tables.value.length && loadedKey === key) return
  await fetchDbs(form.sourceDatasourceId)
  if (form.targetDatasourceId) await fetchDbs(form.targetDatasourceId)
  const list = await http.get(`/datasources/${form.sourceDatasourceId}/tables`, { params: { db: form.sourceDatabase } }) || []
  tables.value = list.map(t => ({
    name: t.name,
    targetTable: t.name,
    rows: t.rows,
    comment: t.comment,
    enabled: false,
    autoCreateTable: true,
    truncateBefore: false,
    recreateTable: false,
    where: '',
    splitPk: '',
    columns: null
  }))
  loadedKey = key
}

function selectAll(v) {
  tables.value.forEach(t => t.enabled = v)
}

function toStep3() {
  if (selectedCount.value === 0) {
    ElMessage.warning('请至少选择一张表')
    return
  }
  step.value = 2
}

// ---------- 第四步：任务详情 ----------
const plan = ref(null)
const planLoading = ref(false)
const openTables = ref([])

const errorLimitText = computed(() => options.errorLimitMode === 'record'
  ? `按条数（${options.errorLimitRecord} 条）`
  : `按比例（${(options.errorLimitPercentage * 100).toFixed(0)}%）`)
const prettyJobJson = computed(() => {
  if (!plan.value || !plan.value.jobJson) return ''
  try { return JSON.stringify(JSON.parse(plan.value.jobJson), null, 2) } catch (e) { return plan.value.jobJson }
})

function toStep4() {
  step.value = 3
  loadPlan()
}

/** 与 save() 相同的请求体，交给后端 /tasks/preview 生成执行计划 */
function buildBody() {
  const payloadTables = tables.value.filter(t => t.enabled).map(t => ({
    sourceTable: t.name,
    targetTable: t.targetTable || t.name,
    enabled: true,
    autoCreateTable: t.autoCreateTable,
    truncateBefore: t.truncateBefore,
    recreateTable: t.recreateTable,
    where: t.where,
    splitPk: t.splitPk,
    columns: (t.columns || []).map(c => ({
      source: c.source,
      sourceType: c.sourceType || '',
      target: c.target,
      selected: c.selected,
      pk: !!c.pk,
      targetType: c.targetType || '',
      targetTypeLen: c.targetTypeLen || ''
    }))
  }))
  const config = JSON.stringify({ tables: payloadTables, options: { ...options } })
  return {
    name: form.name,
    sourceDatasourceId: form.sourceDatasourceId,
    sourceDatabase: form.sourceDatabase,
    targetDatasourceId: form.targetDatasourceId,
    targetDatabase: form.targetDatabase,
    description: form.description,
    config,
    notifyConfig: buildNotifyConfig()
  }
}

async function loadPlan() {
  if (selectedCount.value === 0) {
    ElMessage.warning('请至少选择一张表')
    return
  }
  planLoading.value = true
  plan.value = null
  try {
    plan.value = await http.post('/tasks/preview', buildBody())
    // 单表时默认展开；多表默认全部收起，标题上会显示提醒数量
    openTables.value = plan.value && plan.value.tables.length === 1 ? [0] : []
  } catch (e) {
    // 错误已在拦截器中统一提示
    plan.value = null
  } finally {
    planLoading.value = false
  }
}

async function copyJobJson() {
  try {
    await navigator.clipboard.writeText(prettyJobJson.value)
    ElMessage.success('已复制到剪贴板')
  } catch (e) {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

// ---------- 字段映射 ----------
const mappingVisible = ref(false)
const mappingTable = ref(null)

// 目标类型下拉选项：来自目标库 db_{brand}_field 字典（可在「类型字典」页扩展，如 长文本=>longtext）
const targetTypeOptions = ref([])
const targetOptionsBrand = ref('')

// 「系统默认」裁决缓存：sourceType -> { targetType, sameAsSource }（后端 /meta/resolve-types）
const resolvedMap = ref({})

async function loadTargetTypeOptions() {
  const ds = sources.value.find(d => d.id === form.targetDatasourceId)
  const brand = ds ? ds.type : ''
  if (!brand) {
    targetTypeOptions.value = []
    return
  }
  if (targetOptionsBrand.value === brand) return
  targetOptionsBrand.value = brand
  resolvedMap.value = {}
  try {
    // 字典 KV 语义：label=基础类型, value=默认长度（可空）
    const vals = await http.get('/dict/values', { params: { dictCode: 'db_' + brand + '_field' } }) || []
    targetTypeOptions.value = vals.map(v => ({ label: v.label, value: v.label, len: (v.value || '').trim() }))
  } catch (e) {
    targetTypeOptions.value = []
  }
}

/** 选中字典项：填入默认长度（可空则清空）；手输类型不动长度 */
function onTargetTypeChange(row) {
  if (!row.targetType) {
    row.targetTypeLen = ''
    return
  }
  const opt = targetTypeOptions.value.find(o => o.value === row.targetType)
  if (opt) row.targetTypeLen = opt.len
}

// 批量预演「系统默认」裁决（字典 -> 恒等），供最终类型列展示
async function loadResolvedTypes(row) {
  if (!row.columns || !form.sourceDatasourceId || !form.targetDatasourceId) return
  const types = [...new Set(row.columns.map(c => c.sourceType).filter(Boolean))]
  if (!types.length) return
  try {
    const items = await http.post('/meta/resolve-types', {
      sourceDatasourceId: form.sourceDatasourceId,
      targetDatasourceId: form.targetDatasourceId,
      sourceTypes: types
    }) || []
    const m = {}
    items.forEach(it => { m[it.sourceType] = it })
    resolvedMap.value = m
  } catch (e) {
    resolvedMap.value = {}
  }
}

/** 最终落库类型：显式指定(+长度合成) > 系统默认裁决(字典/恒等)；与后端合成规则一致 */
function finalType(row) {
  const tt = (row.targetType || '').trim()
  if (tt) {
    const len = (row.targetTypeLen || '').trim().replace(/\s/g, '')
    if (len && !tt.includes('(')) return `${tt}(${len})`
    return tt
  }
  const hit = resolvedMap.value[row.sourceType]
  return hit ? hit.targetType : (row.sourceType || '')
}

/** 去括号基名（后端 TypeMapping.baseName 的简化版）：小写 + 去括号及之后内容，用于宽度微差容错 */
function typeBase(t) {
  const s = (t || '').trim().toLowerCase()
  const p = s.indexOf('(')
  return p > 0 ? s.substring(0, p) : s
}

/** 是否红色告警：目标表已存在时缺列（将 ALTER 补齐）或与实际类型不一致（写入可能报错）。
 *  判定优先级：完全一致不告警；后端裁决 issues 告警（带原因）；基名兼容（宽度微差，如 bigint 与 bigint(20)）不告警 */
function needsAttention(row) {
  if (targetExists.value !== true || !mappingTable.value) return false
  if (!row.targetTypeDb) return true
  const f = finalType(row)
  const b = row.targetTypeDb
  if (f === b) return false
  const hit = resolvedMap.value[row.sourceType]
  if (hit && hit.issues && hit.issues.length) return true
  return typeBase(f) !== typeBase(b)
}

function attentionReason(row) {
  if (targetExists.value !== true) return ''
  if (!row.targetTypeDb) return '缺列，执行时 ALTER 补齐'
  const hit = resolvedMap.value[row.sourceType]
  if (hit && hit.issues && hit.issues.length) return hit.issues[0]
  return '与目标实际类型不一致（写入可能报错）'
}

async function openMapping(row) {
  mappingTable.value = row
  mappingVisible.value = true
  loadTargetTypeOptions()
  if (!row.columns) {
    const cols = await http.get(`/datasources/${form.sourceDatasourceId}/columns`,
      { params: { db: form.sourceDatabase, table: row.name } }) || []
    row.columns = cols.map(c => ({
      source: c.name, sourceType: c.type, target: c.name, selected: true, pk: c.pk,
      targetType: '', targetTypeLen: '', targetTypeDb: ''
    }))
  }
  await Promise.all([loadTargetColumns(), loadResolvedTypes(row)])
}

async function loadTargetColumns() {
  const row = mappingTable.value
  if (!row) return
  loadingTargetCols.value = true
  try {
    const cols = await http.get(`/datasources/${form.targetDatasourceId}/columns`,
      { params: { db: form.targetDatabase, table: row.targetTable } })
    targetExists.value = true
    const map = {}
    cols.forEach(c => map[c.name] = c.type)
    row.columns.forEach(c => { c.targetTypeDb = map[c.target] || '' })
  } catch (e) {
    targetExists.value = false
    row.columns.forEach(c => { c.targetTypeDb = '' })
  } finally {
    loadingTargetCols.value = false
  }
}

function selectAllCols(v) {
  if (mappingTable.value) mappingTable.value.columns.forEach(c => c.selected = v)
}

// ---------- 保存 ----------
async function save() {
  const body = buildBody()
  const config = JSON.parse(body.config)
  if (!config.tables.length) {
    ElMessage.warning('没有启用的表')
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await http.put(`/tasks/${form.id}`, body)
    } else {
      await http.post('/tasks', body)
    }
    ElMessage.success('保存成功')
    router.push('/tasks')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.title { font-size: 16px; font-weight: 600; }
.help-icon { margin-left: 8px; color: #909399; cursor: pointer; font-size: 16px; }
.write-mode-row { display: flex; align-items: center; width: 100%; }
.write-mode-row :deep(.el-select) { flex: 1; }
.write-mode-help { max-width: 420px; line-height: 1.8; }
.write-mode-help p { margin: 0 0 6px; }
.write-mode-help p:last-child { margin-bottom: 0; }
.step-actions { margin-top: 18px; text-align: center; }
.table-tools { margin-bottom: 10px; }
/* 第 2 步：卡片占满视口剩余高度，表格自身滚动（固定表头），按钮不被滚出视野 */
.edit-card-fill :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  /* 顶栏 56px + el-main 上下内边距 32px + 卡片内边距 40px + 卡片边框 2px */
  height: calc(100vh - 130px);
}
.edit-card-fill .toolbar,
.edit-card-fill :deep(.el-steps) {
  flex-shrink: 0;
}
.step-pane-fill { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.step-pane-fill .table-wrap { flex: 1; min-height: 0; }
.step-pane-fill .table-tools,
.step-pane-fill .step-actions { flex-shrink: 0; }
/* 第 4 步：详情内容区内部滚动，操作按钮固定 */
.plan-scroll { flex: 1; min-height: 0; overflow-y: auto; padding-right: 4px; }
/* 同步方向：左源右目标，防止方向搞反 */
.direction-bar { display: flex; align-items: stretch; gap: 12px; margin-bottom: 16px; }
.direction-card { flex: 1; border: 1px solid #e4e7ed; padding: 10px 14px; }
.direction-card.source { border-left: 4px solid #409eff; background: #f0f7ff; }
.direction-card.target { border-left: 4px solid #e6a23c; background: #fdf6ec; }
.direction-tag { font-size: 12px; color: #909399; letter-spacing: 1px; }
.direction-card.target .direction-tag { color: #e6a23c; font-weight: 600; }
.direction-name { font-size: 15px; font-weight: 600; margin: 2px 0 4px; }
.direction-meta { font-size: 13px; color: #606266; }
.direction-db { font-size: 13px; color: #606266; margin-top: 2px; }
.direction-arrow {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  color: #e6a23c; gap: 2px; font-size: 12px; flex-shrink: 0; padding: 0 4px;
}
.notify-preview { margin-bottom: 16px; }
.notify-preview-item { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-radius: 4px; background: #f5f7fa; margin-bottom: 6px; }
.notify-preview-url { font-size: 13px; word-break: break-all; }
.notify-preview-body { font-size: 12px; color: #909399; margin-left: auto; }
.plan-section-title { font-size: 15px; font-weight: 600; }
.plan-tables-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.plan-tbl-title { font-weight: 500; }
.plan-tbl-body { padding: 4px 8px; }
.plan-meta { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.prepare-log {
  background: #f6f8fa; border: 1px solid #e4e7ed; border-radius: 4px;
  padding: 10px; font-size: 12px; line-height: 1.7; overflow: auto; max-height: 300px;
  white-space: pre; margin: 0 0 10px; color: #303133;
  font-family: Consolas, Monaco, 'Courier New', monospace;
}
.plan-col-ok { color: #67c23a; font-size: 12px; }
.jobjson-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.jobjson-tip { font-size: 12px; color: #909399; }
.jobjson {
  background: #f6f8fa; border: 1px solid #e4e7ed; border-radius: 4px;
  padding: 12px; font-size: 12px; line-height: 1.6; max-height: 420px; overflow: auto;
}
.mapping-tools { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
/* 目标类型单元格：上行实际类型对比（小灰字），下行用户指定下拉 */
.cell-type { display: flex; flex-direction: column; gap: 2px; }
.actual-type { font-size: 11px; color: #67c23a; line-height: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.actual-missing { color: #c0c4cc; }
.type-final { font-size: 12px; line-height: 16px; }
.type-final-warn .type-final { color: #f56c6c; font-weight: 600; }
.type-final-reason { font-size: 11px; color: #f56c6c; line-height: 14px; }
.opt-default-len { float: right; color: #909399; font-size: 12px; margin-left: 12px; }
.notify-tip {
  font-size: 12px;
  color: #909399;
  background: #f5f7fa;
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 12px;
  line-height: 1.8;
}
.notify-card {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 14px;
  margin-bottom: 12px;
  max-width: 980px;
}
.notify-head { display: flex; gap: 8px; margin-bottom: 12px; }
.notify-url { flex: 1; }
.notify-body { display: flex; gap: 24px; flex-wrap: wrap; }
.notify-col { min-width: 280px; }
.kv-label { font-size: 13px; font-weight: 600; color: #606266; margin-bottom: 6px; }
.kv-row { display: flex; gap: 6px; align-items: center; margin-bottom: 6px; }
.kv-row .el-input { flex: 1; }
</style>
