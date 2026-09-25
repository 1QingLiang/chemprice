<template>
  <div class="user-manage">
    <div class="page-header">
      <h2>用户管理</h2>
      <div style="display:flex;gap:10px;align-items:center">
        <button class="btn-log btn-ann-entry" @click="openAnnBoard">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 11v2a1 1 0 0 0 1 1h2l4 3V7L6 10H4a1 1 0 0 0-1 1z"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/><path d="M18.5 5.5a9 9 0 0 1 0 13"/></svg>
          公告管理
          <span v-if="annCurrent" class="ann-live-dot" title="当前有生效中的公告"></span>
        </button>
        <button class="btn-log" @click="openAiFlags">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l1.9 5.8a2 2 0 0 0 1.3 1.3L21 12l-5.8 1.9a2 2 0 0 0-1.3 1.3L12 21l-1.9-5.8a2 2 0 0 0-1.3-1.3L3 12l5.8-1.9a2 2 0 0 0 1.3-1.3z"/></svg>
          AI 功能
        </button>
        <button class="btn-log" @click="openAllLogs">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15.5 14"/></svg>
          操作日志
        </button>
        <button class="btn-primary" @click="showCreate = true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          新建用户
        </button>
      </div>
    </div>

    <el-dialog v-model="annBoardDlg" title="公告管理" width="880px" top="6vh"
           :close-on-click-modal="false" append-to-body class="ann-board-dlg">
      <div class="ann-card">
      <div class="ann-head">
        <span class="ann-sub">发布后全站顶部走马灯展示 · 支持级别 / 定时上下线</span>
        <span v-if="annEditId" class="ann-editing">正在编辑 #{{ annEditId }}</span>
        <button class="ann-new-btn" @click="openAnnDialog()">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          发布公告
        </button>
      </div>

      <div v-if="annCurrent" class="ann-cur" :class="annCurrent.level">
        <span class="ann-cur-tag">当前生效</span>
        <span class="ann-cur-text">{{ annCurrent.content }}</span>
        <span class="ann-cur-time">{{ annCurrent.created_at }}</span>
        <el-button size="small" type="danger" plain @click="doDisableAnn(annCurrent.id)">停用</el-button>
      </div>
      <div v-else class="ann-cur ann-none">当前没有生效中的公告</div>

      <div class="ann-history">
        <div class="ann-tools">
          <span class="ann-h-title">发布记录</span>
          <el-input v-model="annKw" size="small" placeholder="搜索内容" clearable class="ann-kw"
                    @keyup.enter="loadAnnList(1)" @clear="loadAnnList(1)" />
          <el-button size="small" @click="loadAnnList(1)">搜索</el-button>
          <span class="ann-total">共 {{ annTotal }} 条</span>
        </div>

        <div v-if="!annList.length" class="ann-empty">暂无记录</div>
        <div v-for="a in annList" :key="a.id" class="ann-h-row" :class="{ cur: a.enabled }">
          <span class="ann-h-dot" :class="{ on: a.enabled }"></span>
          <span class="ann-lv-tag" :class="a.level">{{ levelText(a.level) }}</span>
          <span class="ann-h-text" :title="a.content">{{ a.content }}</span>
          <span class="ann-h-time">{{ shortTime(a.created_at) }}</span>
          <div class="ann-h-ops">
            <button class="ann-op" @click="startEditAnn(a)">编辑</button>
            <button v-if="a.enabled" class="ann-op danger" @click="doDisableAnn(a.id)">停用</button>
            <button v-else class="ann-op primary" @click="doEnableAnn(a.id)">启用</button>
            <button class="ann-op danger" @click="doRemoveAnn(a)">删除</button>
          </div>
        </div>

        <div class="ann-pager" v-if="annTotal > annSize">
          <el-pagination small background layout="prev, pager, next" :total="annTotal"
                         :page-size="annSize" :current-page="annPage" @current-change="loadAnnList" />
        </div>
      </div>
      </div>
    </el-dialog>

    <!-- 发布 / 编辑公告 弹窗 -->
    <el-dialog v-model="annDlg" :title="annEditId ? '编辑公告' : '发布公告'" width="640px"
               :close-on-click-modal="false" append-to-body @closed="onAnnDlgClosed">
      <div class="ann-dlg">
        <div class="ann-ed-row">
          <span class="ann-lbl">级别</span>
          <div class="ann-levels">
            <button v-for="lv in ANN_LEVELS" :key="lv.v" class="ann-lv" :class="[lv.v, { on: annLevel === lv.v }]"
                    @click="annLevel = lv.v">{{ lv.t }}</button>
          </div>
        </div>
        <div class="ann-ed-row">
          <span class="ann-lbl">定时</span>
          <el-date-picker v-model="annRange" type="datetimerange" size="small"
                          range-separator="→" start-placeholder="立即生效" end-placeholder="长期有效"
                          format="MM-DD HH:mm" value-format="YYYY-MM-DD HH:mm:ss"
                          class="ann-dp" :teleported="false" />
        </div>

        <el-input
          v-model="annDraft"
          type="textarea"
          :rows="5"
          maxlength="500"
          show-word-limit
          placeholder="输入公告内容（500 字以内），如：平台已上线公众号问价功能，关注「化工散文」发消息即可查价"
        />

        <!-- 走马灯预览 -->
        <div class="ann-preview" :class="annLevel">
          <span class="ann-pv-horn">{{ (ANN_LEVELS.find(l => l.v === annLevel) || {}).icon || '📢' }}</span>
          <span class="ann-pv-text">{{ annDraft || '（预览）输入内容后，这里模拟顶部走马灯的实际效果' }}</span>
        </div>
        <div class="ann-tip">
          提示：级别决定顶部走马灯配色；「定时」留空表示<strong>立即生效、长期有效</strong>，
          填了会到点自动上线、过期自动下线。
        </div>
      </div>
      <template #footer>
        <el-button @click="annDlg = false">取消</el-button>
        <el-button type="primary" :loading="annPublishing" @click="doPublishAnn">
          {{ annEditId ? '保存修改' : '发 布' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog title="AI 功能开关" v-model="aiDlg" width="480px" append-to-body>
      <div class="ai-flag-row">
        <div class="ai-flag-info">
          <div class="ai-flag-name">AI 代发供需</div>
          <div class="ai-flag-desc">用户在 AI 问价里说「供应XX / 求购XX」时，AI 自动发布到供需广场。
关闭后 AI 会引导用户到「供需广场」手动发布。改动实时生效，无需重启。</div>
        </div>
        <el-switch v-model="aiPublish" :loading="aiSaving" @change="saveAiFlag" />
      </div>
    </el-dialog>

    <div class="card card-users">
      <div class="um-bar">
        <div class="um-tabs">
          <button v-for="t in STATUS_TABS" :key="t.k" class="um-tab"
                  :class="{ on: statusFilter === t.k }" @click="statusFilter = t.k">
            {{ t.t }}<b>{{ tabCount(t.k) }}</b>
          </button>
        </div>
        <span v-if="statusFilter === 'off'" class="um-note">
          禁用账号无法登录，也不计入用户看板统计；点「详情」可重新启用
        </span>
      </div>
      <table class="table">
        <thead>
          <tr>
            <th>用户名</th><th>昵称</th><th>邮箱</th><th>角色</th><th>状态</th><th>实名</th>
            <th>标点地图</th>
            <th class="th-sort" :class="{ on: sortActive }" @click="toggleActiveSort"
                :title="sortTip">
              最后活跃<span class="sort-ar">{{ sortIcon }}</span>
            </th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in displayUsers" :key="u.id" :class="{ 'row-off': u.status === 0 }">
            <td class="font-semibold">
              {{ u.username }}
              <span v-if="u.isTest === 1" class="badge badge-test"
                    title="自动化测试账号，不计入「用户看板」统计">测试</span>
            </td>
            <td>{{ u.nickname || '—' }}</td>
            <td class="cell-mail" :title="u.email || ''">{{ u.email || '—' }}</td>
            <td>
              <span class="badge" :class="u.role === 'ADMIN' ? 'badge-r' : 'badge-m'">{{ u.role }}</span>
              <span v-if="u.supplierStatus === 2" class="badge sup-ok"
                    :title="u.supplierCompany ? ('已认证供应商：' + u.supplierCompany) : '已认证供应商'">已认证</span>
              <span v-else-if="u.supplierStatus === 1" class="badge sup-wait" title="供应商认证审核中">认证中</span>
              <span v-else-if="u.supplierStatus === 3" class="badge sup-wait" title="认证已驳回或被管理员要求重新认证，可重新提交申请">重新认证</span>
            </td>
            <td><span class="badge" :class="u.status === 1 ? 'badge-g' : 'badge-a'">{{ u.status === 1 ? '启用' : '禁用' }}</span></td>
            <td class="cell-rn" :title="u.realnameName ? ('实名姓名：' + u.realnameName + '（完整身份证号请点「详情」查看）') : '未实名认证'">
              <span v-if="u.realnameName" class="rn-name">{{ u.realnameName }}</span>
              <span v-else-if="u.realnameStatus === 1" style="color:var(--ink3, #71717a)">已实名</span>
              <span v-else style="color:var(--ink4, #a1a1aa)">—</span>
            </td>
            <td class="cell-emap">
              <template v-if="u.role === 'ADMIN'">
                <span class="emap-na">始终可用</span>
              </template>
              <template v-else-if="u.enterpriseMapStatus === 'on'">
                <span class="badge emap-on">已开通</span>
                <span class="emap-exp" :class="{ 'exp-soon': isEmapSoon(u) }">
                  {{ u.enterpriseMapExpireDate
                      ? ('至 ' + u.enterpriseMapExpireDate + (u.enterpriseMapDaysLeft !== null ? '（剩 ' + u.enterpriseMapDaysLeft + ' 天）' : ''))
                      : '永久' }}
                </span>
              </template>
              <template v-else-if="u.enterpriseMapStatus === 'expired'">
                <span class="badge emap-over">已过期</span>
                <span class="emap-exp exp-over">{{ u.enterpriseMapExpireDate }}</span>
              </template>
              <template v-else>
                <span class="emap-na">未开通</span>
              </template>
            </td>
            <td class="cell-t"
                :title="u.lastActiveAt ? '最近一次系统操作：' + fmtTime(u.lastActiveAt) + '（含登录、查价、导出等）' : '该账号暂无任何操作记录'">
              <span v-if="u.lastActiveAt">{{ fmtShort(u.lastActiveAt) }}</span>
              <span v-else style="color:var(--ink4, #a1a1aa)">从未使用</span>
            </td>
            <td class="actions">
              <span class="action-link" @click="editUser(u)">详情</span>
              <span class="action-link" @click="viewLogs(u)">日志</span>
              <span class="action-link danger"
                    v-if="u.username !== 'admin' && u.status === 1" @click="deleteUser(u)">删除</span>
            </td>
          </tr>
          <tr v-if="!displayUsers.length"><td colspan="9" class="empty-row">暂无用户</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 创建/编辑弹窗 -->
    <AppModal :model-value="showCreate || !!editingUser" bare @update:model-value="closeModal">
      <div class="modal-box" :class="{ 'modal-user': editingUser }">
        <h3>{{ editingUser ? '用户详情 · ' + editingUser.username : '新建用户' }}</h3>
        <div v-if="editingUser" class="ud-info">
          <div class="ud-row"><span>邮箱</span><b class="ud-wrap">{{ editingUser.email || '—' }}</b></div>
          <div class="ud-row"><span>手机号</span><b>{{ editingUser.phone || '未绑定' }}</b></div>
          <div class="ud-row"><span>实名认证</span>
            <b><span class="badge" :class="editingUser.realnameStatus === 1 ? 'badge-g' : 'badge-a'">{{ editingUser.realnameStatus === 1 ? '已实名' : '未实名' }}</span></b></div>
          <div class="ud-row" v-if="editingUser.realnameStatus === 1"><span>真实姓名</span>
            <b>{{ editingUser.realnameName || '—' }}</b></div>
          <div class="ud-row" v-if="editingUser.realnameStatus === 1"><span>身份证号</span>
            <b class="ud-idc">
              <template v-if="editingUser.realnameIdcardFull">{{ editingUser.realnameIdcardFull }}</template>
              <template v-else>{{ editingUser.realnameIdcard || '—' }}
                <em class="ud-idc-note">该系统 2026-09-17 起留存完整号，此前实名的用户仅存脱敏值且无法补回</em>
              </template>
            </b></div>
          <div class="ud-row"><span>企业认证</span>
            <b class="ud-wrap">
              <span v-if="editingUser.supplierStatus === 2" class="badge sup-ok">已认证</span>
              <span v-else-if="editingUser.supplierStatus === 1" class="badge sup-wait">认证中</span>
              <span v-else-if="editingUser.supplierStatus === 3" class="badge sup-wait">重新认证</span>
              <span v-else class="badge badge-a">未认证</span>
              <em v-if="editingUser.supplierCompany" class="ud-co">{{ editingUser.supplierCompany }}</em>
            </b></div>
          <div class="ud-row" v-if="editingUser.supplierValidUntil"><span>认证有效期</span>
            <b>至 {{ fmtTime(editingUser.supplierValidUntil).slice(0, 10) }}</b></div>
          <div class="ud-row"><span>创建时间</span><b>{{ fmtTime(editingUser.createdAt) }}</b></div>
          <div class="ud-row"><span>最后登录</span>
            <b class="ud-wrap">{{ editingUser.lastLoginAt ? fmtTime(editingUser.lastLoginAt) : '—' }}
              <em v-if="editingUser.lastLoginIp" class="ud-ip">IP: {{ editingUser.lastLoginIp }}</em>
            </b></div>
          <div class="ud-row"><span>最后活跃</span><b>{{ editingUser.lastActiveAt ? fmtTime(editingUser.lastActiveAt) : '从未使用' }}</b></div>
          <div class="ud-row"><span>授权商品</span>
            <b>
              <template v-if="editingUser.role === 'ADMIN'">全部商品</template>
              <template v-else-if="editingUser.permissions && editingUser.permissions.length">
                {{ Object.keys(editingUser.permCategories).length }} 类 / {{ editingUser.permissions.length }} 项
              </template>
              <template v-else>未授权</template>
            </b>
            <button v-if="editingUser.role === 'USER'" type="button" class="ud-perm-btn"
                    @click="showPerms(editingUser)">管理数据权限 →</button>
          </div>
        </div>
        <div class="ud-actions">
          <button type="button" class="ud-reset" @click="resetVerify(editingUser, 'realname')"
                  title="用户实名核验 3 次用完后，管理员重置为 0">重置实名核验次数</button>
          <button type="button" class="ud-reset" @click="resetVerify(editingUser, 'license')"
                  title="用户执照上传 3 次用完后，管理员重置为 0">重置执照上传次数</button>
          <button type="button" class="ud-reset ud-danger" v-if="editingUser.realnameStatus === 1"
                  @click="resetRealname(editingUser)"
                  title="清空实名状态与身份证信息，该用户需重新完成实名认证">解除实名（要求重新认证）</button>
        </div>
        <form @submit.prevent="saveUser">
          <div class="form-group" v-if="!editingUser">
            <label>用户名</label>
            <input v-model="form.username" :disabled="!!editingUser" placeholder="请输入用户名" />
          </div>
          <div class="form-group">
            <label>昵称</label>
            <input v-model="form.nickname" placeholder="请输入昵称" />
          </div>
          <div class="form-group">
            <label>邮箱</label>
            <input v-model="form.email" type="email" placeholder="请输入邮箱" />
          </div>
          <div class="form-group">
            <label>手机号{{ editingUser ? '（管理员代改，留空不修改）' : '' }}</label>
            <input v-model="form.phone" class="num" maxlength="11"
                   :placeholder="editingUser ? (editingUser.phone || '未绑定，填入后保存即绑定') : '请输入 11 位手机号（选填）'" />
          </div>
          <div class="form-group">
            <label>{{ editingUser ? '新密码（留空不修改）' : '密码' }}</label>
            <input v-model="form.password" type="password" :placeholder="editingUser ? '留空则不修改密码' : '请输入密码'" />
          </div>
          <div class="form-group">
            <label>角色</label>
            <select v-model="form.role">
              <option value="USER">普通用户</option>
              <option value="ADMIN">管理员</option>
            </select>
          </div>
          <div class="form-group">
            <label>状态</label>
            <select v-model.number="form.status">
              <option :value="1">启用</option>
              <option :value="0" :disabled="editingUser && editingUser.username === 'admin'">禁用</option>
            </select>
          </div>
          <div class="form-group">
            <label>数据导出权限</label>
            <select v-model.number="form.exportPermission">
              <option :value="0">禁止导出</option>
              <option :value="1">允许导出</option>
            </select>
          </div>
          <div class="form-group" v-if="form.role === 'ADMIN'">
            <label>标点地图</label>
            <p class="form-hint">管理员账号<b>始终可用</b>，无需开通，也不受有效期限制。</p>
          </div>
          <div class="form-group" v-else>
            <label>标点地图</label>
            <select v-model.number="form.enterpriseMapEnabled">
              <option :value="0">未开通（菜单可见，点进去是开通引导）</option>
              <option :value="1">已开通</option>
            </select>
          </div>
          <div class="form-group" v-if="form.role !== 'ADMIN' && form.enterpriseMapEnabled === 1">
            <label>开通时长</label>
            <select v-model="form.emapDuration">
              <option value="permanent">永久有效（不设到期日）</option>
              <option value="30">30 天（1 个月）</option>
              <option value="90">90 天（1 个季度）</option>
              <option value="180">180 天（半年）</option>
              <option value="365">365 天（1 年）</option>
              <option value="custom">自定义到期日…</option>
            </select>
            <input v-if="form.emapDuration === 'custom'" type="date" v-model="form.emapCustomDate"
                   class="date-inp" :min="todayYmd" />
            <p class="form-hint" :class="{ 'hint-warn': emapPreviewWarn }">{{ emapPreviewText }}</p>
          </div>
          <div class="form-actions">
            <button type="button" class="btn-cancel" @click="closeModal">取消</button>
            <button type="submit" class="btn-primary" :disabled="saving">{{ saving ? '保存中...' : '保存' }}</button>
          </div>
          <div v-if="formError" class="form-error">{{ formError }}</div>
        </form>
      </div>
    </AppModal>

    <!-- 权限分配弹窗 -->
    <AppModal v-model="showPermModal" bare>
      <div class="modal-box modal-wide">
        <h3>{{ permUser?.username }} 的数据权限</h3>
        <p class="perm-hint">管理员将可见全部数据；普通用户只能查看已授权的商品数据。</p>

        <!-- 当前已授权（按大类分组） -->
        <div class="perm-section">
          <div class="perm-sec-head">
            <h4>已授权商品</h4>
            <span class="perm-summary" v-if="userPerms.length">共 {{ userPerms.length }} 个，覆盖 {{ groupedPerms.length }} 个大类</span>
          </div>
          <div v-if="userPerms.length" class="perm-groups">
            <div v-for="g in groupedPerms" :key="g.category" class="perm-group">
              <div class="perm-group-head">
                <span class="perm-cat-tag">{{ g.category }}</span>
                <span class="perm-cat-count">{{ g.items.length }} 个</span>
                <button class="perm-revoke" @click="revokeCatPerm(g.category)">取消该类全部</button>
              </div>
              <div class="perm-list">
                <div v-for="p in g.items" :key="p.id" class="perm-item">
                  <span class="perm-tag">{{ p.varieties_name }} (ID:{{ p.varieties_id }})</span>
                  <span class="perm-expire">永久</span>
                  <button class="perm-revoke" @click="revokePerm(p)">取消</button>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="perm-empty">暂未授权任何商品</div>
        </div>

        <!-- 按大类快速开通 -->
        <div class="perm-section">
          <h4>按大类开通权限</h4>
          <div class="perm-add-row">
            <el-select v-model="catGrantCategory" filterable placeholder="选择产品大类" style="width:240px">
              <el-option value="" label="请选择大类" disabled />
              <el-option v-for="cat in permCategories" :key="cat" :value="cat" :label="`${cat}（${catProductCount(cat)} 个商品）`" />
            </el-select>
            <el-button type="success" @click="grantCatPerm" :disabled="!catGrantCategory">开通该大类</el-button>
          </div>
        </div>

        <!-- 单个商品授权 -->
        <div class="perm-section">
          <h4>按商品单个添加</h4>
          <div class="perm-add-row">
            <el-select v-model="selectedVarietyId" filterable placeholder="选择商品" style="width:240px">
              <el-option value="" label="请选择商品" disabled />
              <el-option-group v-for="g in allCommodityGroups" :key="g.category" :label="g.category">
                <el-option v-for="c in g.items" :key="c.varietiesId"
                  :value="c.varietiesId"
                  :disabled="userPerms.some(p => p.varieties_id === c.varietiesId)"
                  :label="c.name + ' (' + c.varietiesId + ')'" />
              </el-option-group>
            </el-select>
            <el-button type="primary" @click="grantPerm" :disabled="!selectedVarietyId">授权</el-button>
          </div>
        </div>

        <!-- 邮件推送额度 -->
        <div class="perm-section" v-if="permUser && permUser.role !== 'ADMIN'">
          <h4>邮件推送商品额度（跨任务共享）</h4>
          <div class="perm-add-row" style="flex-wrap:wrap">
            <span class="perm-hint" style="width:100%">该用户所有推送任务合计最多可推送的商品条数（默认 1 条；任务数量不限，商品总数受此额度约束，需更多由管理员调高）</span>
            <el-input-number v-model="pushQuotaVal" :min="0" :max="50" style="width:130px" />
            <el-button type="warning" @click="savePushQuota" :disabled="pushQuotaSaving">{{ pushQuotaSaving ? '保存中...' : '保存额度' }}</el-button>
            <span v-if="pushQuotaMsg" style="font-size:12px;color:#059669">{{ pushQuotaMsg }}</span>
          </div>
        </div>

        <div class="form-actions" style="margin-top:16px">
          <button class="btn-cancel" @click="showPermModal = false">关闭</button>
        </div>
      </div>
    </AppModal>

    <!-- 操作日志弹窗（统一：全部日志 / 按用户过滤） -->
    <AppModal v-model="showAllLogs" bare>
      <div class="modal-box modal-wide log-modal" @click.stop>
        <div class="log-head">
          <h3>{{ logFilter.userId ? '用户「' + logFilter.username + '」的操作日志' : '操作日志' }}</h3>
          <button class="btn-cancel" style="padding:5px 14px;font-size:13px" @click="showAllLogs = false">✕</button>
        </div>
        <div v-if="logFilter.userId" class="log-user-banner">
          <span>当前仅显示用户「{{ logFilter.username }}」的日志</span>
          <button class="btn-clear-filter" @click="clearUserFilter">查看全部</button>
        </div>
        <!-- 筛选区 -->
        <div class="log-toolbar">
          <el-select v-model="logFilter.type" clearable style="width:150px" @change="loadAllLogs(1)">
            <el-option v-for="opt in TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
          <el-input v-model="logFilter.username" placeholder="按用户名搜索" clearable style="width:170px" :disabled="!!logFilter.userId" @keyup.enter="loadAllLogs(1)" />
          <el-date-picker v-model="logFilter.dateRange" type="daterange" range-separator="至"
            start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD"
            style="width:250px" @change="loadAllLogs(1)" />
          <el-button type="primary" size="small" @click="loadAllLogs(1)">查询</el-button>
          <el-button size="small" @click="resetLogFilter">重置</el-button>
          <span class="pagination-info" style="margin-left:auto">共 {{ allLogsTotal }} 条</span>
        </div>
        <div class="log-scroll">
        <table class="table log-table">
          <thead><tr><th style="width:170px">时间</th><th style="width:120px">用户</th><th style="width:260px">操作</th><th style="width:76px">级别</th><th>详情</th><th style="width:120px">IP</th></tr></thead>
          <tbody>
            <tr v-for="log in allLogs" :key="log.id">
              <td class="cell-time">{{ fmtTime(log.createdAt) }}</td>
              <td><span v-if="log.username === 'SYSTEM'" class="sys-tag">系统</span><span v-else style="font-weight:500">{{ log.username || '—' }}</span></td>
              <td>
                <span class="log-type-dot" :class="typeMeta(logCat(log)).cls" :title="typeMeta(logCat(log)).label"></span>
                <span class="log-act" :title="log.action">{{ actionLabel(log.action) }}</span>
              </td>
              <td><span class="badge" :class="levelBadge(log.level)">{{ levelLabel(log.level) }}</span></td>
              <td class="cell-detail">{{ log.detail || '—' }}</td>
              <td class="cell-ip">{{ log.ip || '—' }}</td>
            </tr>
            <tr v-if="!allLogs.length"><td colspan="6" class="empty-row">暂无日志</td></tr>
          </tbody>
        </table>
        </div>
        <!-- 分页 -->
        <div class="log-pager">
          <div class="pagination-info">第 {{ logPage }} / {{ logPages }} 页</div>
          <div style="display:flex;gap:6px">
            <button class="page-btn" :disabled="logPage <= 1" @click="loadAllLogs(logPage-1)">‹</button>
            <button class="page-btn" :disabled="logPage >= logPages" @click="loadAllLogs(logPage+1)">›</button>
          </div>
        </div>
        <div class="form-actions">
          <button class="btn-cancel" @click="showAllLogs = false">关 闭</button>
        </div>
      </div>
    </AppModal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import AppModal from '../components/AppModal.vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import {
  getUsers, createUser, updateUser, deleteUser as delUser,
  getUserPermissions, grantPermission, revokePermission,
  grantCategoryPermission, revokeCategoryPermission,
  getCommodities, getAuditLogs,
  adminGetPushConfig, adminSetPushQuota,
  getCurrentAnnouncement, publishAnnouncement, updateAnnouncement, enableAnnouncement,
  disableAnnouncement, removeAnnouncement, listAnnouncements,
  resetUserVerify, resetUserRealname, getAiFlags, setAiFlags
} from '../api/index'
import { groupByCategory } from '../utils/commodityGroups'
import gsap from 'gsap'

// ===== 公告管理 v2 =====
const ANN_LEVELS = [
  { v: 'normal', t: '普通', icon: '📢' },
  { v: 'important', t: '重要', icon: '⚠️' },
  { v: 'maintenance', t: '维护', icon: '🔧' },
]
const annDraft = ref('')
const annCurrent = ref(null)
const annList = ref([])
const annPublishing = ref(false)
const annLevel = ref('normal')      // 公告级别
const annRange = ref(null)          // [生效, 失效]，null 表示不限
const annEditId = ref(null)         // 非空即处于编辑态
const annDlg = ref(false)           // 发布/编辑弹窗
const annBoardDlg = ref(false)      // 公告管理板块弹窗
const annKw = ref('')               // 搜索关键词
const annPage = ref(1)
const annSize = ref(10)
const annTotal = ref(0)

function levelText(v) {
  const hit = ANN_LEVELS.find((l) => l.v === v)
  return hit ? hit.t : '普通'
}
/** 列表时间只显示到分钟，省得被年份挤占 */
function shortTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }

async function loadAnn() {
  try {
    const c = await getCurrentAnnouncement()
    annCurrent.value = (c.code === 200 && c.data) ? c.data : null
    await loadAnnList(annPage.value)
  } catch (e) { console.error(e) }
}

async function loadAnnList(page) {
  if (page) annPage.value = page
  try {
    const r = await listAnnouncements({
      keyword: annKw.value || undefined, page: annPage.value, size: annSize.value,
    })
    const d = (r.code === 200 && r.data) ? r.data : null
    annList.value = d ? (d.list || []) : []
    annTotal.value = d ? (d.total || 0) : 0
  } catch (e) { console.error(e) }
}

/** 发布 / 保存修改 */
async function doPublishAnn() {
  const content = (annDraft.value || '').trim()
  if (!content) { ElMessage.warning('请输入公告内容'); return }
  const payload = {
    content,
    level: annLevel.value,
    startAt: annRange.value && annRange.value[0] ? annRange.value[0] : null,
    endAt: annRange.value && annRange.value[1] ? annRange.value[1] : null,
  }
  annPublishing.value = true
  try {
    const r = annEditId.value
      ? await updateAnnouncement(annEditId.value, payload)
      : await publishAnnouncement(payload)
    if (r.code === 200) {
      ElMessage.success(annEditId.value ? '已保存修改' : '公告已发布')
      annDlg.value = false
      await loadAnn()
    } else ElMessage.error(r.message || '操作失败')
  } catch (e) { ElMessage.error('操作失败') } finally { annPublishing.value = false }
}

function resetAnnForm() {
  annDraft.value = ''
  annLevel.value = 'normal'
  annRange.value = null
  annEditId.value = null
}
function cancelAnnEdit() { resetAnnForm() }

/** 打开公告管理板块弹窗（打开时才拉最新数据） */
function openAnnBoard() {
  annBoardDlg.value = true
  loadAnn()
}

/** 打开空白发布弹窗 */
function openAnnDialog() {
  resetAnnForm()
  annDlg.value = true
}
/** 弹窗关闭后清空表单（下次打开是干净的） */
function onAnnDlgClosed() { resetAnnForm() }

/** 把某条记录载入编辑区 */
function startEditAnn(a) {
  annEditId.value = a.id
  annDraft.value = a.content || ''
  annLevel.value = a.level || 'normal'
  annRange.value = (a.start_at || a.end_at) ? [a.start_at || '', a.end_at || ''] : null
  annDlg.value = true
}

async function doDisableAnn(id) {
  try {
    const r = await disableAnnouncement(id)
    if (r.code === 200) { ElMessage.success('已停用'); await loadAnn() }
    else ElMessage.error(r.message || '操作失败')
  } catch (e) { ElMessage.error('操作失败') }
}

async function doEnableAnn(id) {
  try {
    const r = await enableAnnouncement(id)
    if (r.code === 200) { ElMessage.success('已启用（原公告自动停用）'); await loadAnn() }
    else ElMessage.error(r.message || '操作失败')
  } catch (e) { ElMessage.error('操作失败') }
}

/** 删除是不可恢复的，必须二次确认 */
async function doRemoveAnn(a) {
  try {
    await ElMessageBox.confirm('删除后不可恢复，确定删除这条公告吗？', '删除公告',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch (e) { return }   // 用户取消
  try {
    const r = await removeAnnouncement(a.id)
    if (r.code === 200) {
      ElMessage.success('已删除')
      if (annEditId.value === a.id) resetAnnForm()
      // 删掉当前页最后一条时要回退一页
      if (annList.value.length === 1 && annPage.value > 1) annPage.value -= 1
      await loadAnn()
    } else ElMessage.error(r.message || '删除失败')
  } catch (e) { ElMessage.error('删除失败') }
}

const users = ref([])
const showCreate = ref(false)
const editingUser = ref(null)
const showAllLogs = ref(false)
const allLogs = ref([])
const allLogsTotal = ref(0)
const logPage = ref(1)
const logPages = ref(1)
const logPageSize = 30
const logFilter = ref({ type: '', userId: null, username: '', dateRange: null })
const saving = ref(false)
const formError = ref('')
const form = ref({ username: '', nickname: '', email: '', phone: '', password: '', role: 'USER', status: 1, exportPermission: 0, enterpriseMapEnabled: 0, emapDuration: 'permanent', emapCustomDate: '' })

/* ---------- 标点地图「开通时长」 ---------- */
// 仅「标点地图」这类单独开通的功能页权限有有效期；数据权限永久免费，不设到期日。
const todayYmd = (() => {
  const d = new Date(), p = x => String(x).padStart(2, '0')
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
})()

function addDaysYmd(n) {
  const d = new Date(); d.setDate(d.getDate() + n)
  const p = x => String(x).padStart(2, '0')
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
}

/** 距今天数：0=今天到期、正数=还有几天、负数=已过期；null=无法解析 */
function daysUntilYmd(s) {
  if (!s) return null
  const a = new Date(String(s).slice(0, 10) + 'T00:00:00')
  const b = new Date(todayYmd + 'T00:00:00')
  const n = Math.round((a - b) / 86400000)
  return isNaN(n) ? null : n
}

/** 表单最终提交的到期日：'' = 永久 */
const emapResolvedDate = computed(() => {
  if (form.value.enterpriseMapEnabled !== 1) return ''
  const dur = form.value.emapDuration
  if (dur === 'custom') return form.value.emapCustomDate || ''
  if (!dur || dur === 'permanent') return ''
  const n = parseInt(dur, 10)
  return n > 0 ? addDaysYmd(n) : ''
})

const emapPreviewText = computed(() => {
  if (form.value.enterpriseMapEnabled !== 1) return '未开通时，用户进「标点地图」会看到开通引导。'
  const d = emapResolvedDate.value
  if (!d) return '有效期：永久（不设到期日）'
  const left = daysUntilYmd(d)
  const tail = left === null ? '' : (left < 0 ? '，该日期已过期' : '，约 ' + left + ' 天后到期')
  return '有效期至 ' + d + '（含当日有效，次日起失效）' + tail
})

const emapPreviewWarn = computed(() => {
  const d = emapResolvedDate.value
  if (!d) return false
  const left = daysUntilYmd(d)
  return left !== null && left < 0
})

function isEmapSoon(u) {
  const left = u && u.enterpriseMapDaysLeft
  return typeof left === 'number' && left >= 0 && left <= 7
}

// 权限
const showPermModal = ref(false)
const permUser = ref(null)
const userPerms = ref([])
const allCommodities = ref([])
const allCommodityGroups = computed(() => groupByCategory(allCommodities.value))
const permCategories = computed(() => allCommodityGroups.value.map(g => g.category))
const selectedVarietyId = ref('')
const grantExpireDate = ref('')
// 按大类快速开通
const catGrantCategory = ref('')
const catGrantExpire = ref('')
// 邮件推送额度
const pushQuotaVal = ref(1)
const pushQuotaMsg = ref('')
const pushQuotaSaving = ref(false)

function catProductCount(cat) {
  return allCommodities.value.filter(c => (c.category || '未分类') === cat).length
}

// 已授权按大类分组
const groupedPerms = computed(() => {
  const map = new Map()
  for (const p of userPerms.value) {
    const cat = p.category || '未分类'
    if (!map.has(cat)) map.set(cat, [])
    map.get(cat).push(p)
  }
  return [...map.entries()]
    .sort((a, b) => a[0] === '未分类' ? 1 : b[0] === '未分类' ? -1 : a[0].localeCompare(b[0], 'zh-Hans-CN'))
    .map(([category, items]) => ({ category, items }))
})

function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '—' }
// 列表内紧凑显示 MM-DD HH:MM（完整时间放 title）
function fmtShort(t) { return t ? String(t).replace('T', ' ').slice(5, 16) : '—' }
function isExpireOver(d) { return d && new Date(d) < new Date() }
function isExpireSoon(d) { if (!d) return false; const diff = (new Date(d) - new Date()) / 86400000; return diff > 0 && diff <= 30 }

function closeModal() {
  showCreate.value = false; editingUser.value = null
  form.value = { username: '', nickname: '', email: '', password: '', role: 'USER', status: 1, exportPermission: 0, enterpriseMapEnabled: 0, emapDuration: 'permanent', emapCustomDate: '' }
  formError.value = ''
}

// ===== AI 功能开关 =====
const aiDlg = ref(false)
const aiPublish = ref(true)
const aiSaving = ref(false)
async function openAiFlags() {
  aiDlg.value = true
  try { const res = await getAiFlags(); if (res.code === 200) aiPublish.value = !!res.data.publish } catch (e) { console.error(e) }
}
async function saveAiFlag() {
  aiSaving.value = true
  try {
    const res = await setAiFlags({ publish: aiPublish.value })
    if (res.code === 200) ElMessage.success(aiPublish.value ? '已开启 AI 代发供需' : '已关闭 AI 代发供需')
    else ElMessage.error(res.message || '保存失败')
  } catch (e) { ElMessage.error('保存失败'); console.error(e) }
  finally { aiSaving.value = false }
}

async function resetVerify(u, type) {
  const label = type === 'realname' ? '实名核验次数' : '执照上传次数'
  try {
    await ElMessageBox.confirm(`确认重置 ${u.username} 的${label}？`, '重置确认', { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' })
  } catch (e) { return }
  const res = await resetUserVerify(u.id, type)
  if (res.code === 200) ElMessage.success('已重置' + label)
  else ElMessage.error(res.message || '重置失败')
}

async function resetRealname(u) {
  try {
    await ElMessageBox.confirm(
      `解除后立即清空 ${u.username} 的实名状态与身份证信息：` +
      `无法发布求购/供应、供需广场入口消失，需重新完成实名认证（重新核验时将留存完整身份证号）。`,
      '解除实名确认', { type: 'warning', confirmButtonText: '解除实名', cancelButtonText: '取消' })
  } catch (e) { return }
  const res = await resetUserRealname(u.id)
  if (res.code === 200) {
    ElMessage.success('已解除实名，站内信已通知用户重新认证')
    closeModal()
    await loadUsers()
  } else ElMessage.error(res.message || '解除失败')
}

function editUser(u) {
  editingUser.value = u
  // 已有到期日的按「自定义到期日」回显 → 不改动时长时保存是幂等的（不会顺延）
  const emapEd = u.enterpriseMapExpireDate || ''
  form.value = { username: u.username, nickname: u.nickname || '', email: u.email || '', phone: u.phone || '', password: '', role: u.role, status: u.status, exportPermission: u.exportPermission || 0, enterpriseMapEnabled: u.enterpriseMapEnabled || 0,
    emapDuration: emapEd ? 'custom' : 'permanent', emapCustomDate: emapEd }
}

async function saveUser() {
  saving.value = true; formError.value = ''
  try {
    if (editingUser.value) {
      // ⛔ 管理员始终具备标点地图权限：这两个字段一律不要提交 ——
      //    否则后端会返回「管理员始终具备该权限，无需关闭」，导致管理员账号根本无法保存。
      const body = { role: form.value.role, status: form.value.status, exportPermission: form.value.exportPermission }
      if (form.value.role !== 'ADMIN') {
        body.enterpriseMapEnabled = form.value.enterpriseMapEnabled
        body.enterpriseMapExpireDate = emapResolvedDate.value || ''
      }
      if (form.value.nickname) body.nickname = form.value.nickname
      if (form.value.email) body.email = form.value.email
      if (form.value.phone) body.phone = form.value.phone.trim()
      if (form.value.password) body.password = form.value.password
      const res = await updateUser(editingUser.value.id, body)
      if (res.code !== 200) { formError.value = res.message; return }
    } else {
      const res = await createUser(form.value)
      if (res.code !== 200) { formError.value = res.message; return }
    }
    closeModal(); await loadUsers()
  } catch (e) { formError.value = e?.response?.data?.message || '操作失败' }
  finally { saving.value = false }
}

async function deleteUser(u) {
  try {
    await ElMessageBox.confirm(`确认删除用户 ${u.username}？`, '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    const res = await delUser(u.id)
    if (res.code === 200) { await loadUsers(); ElMessage.success('删除成功') }
    else ElMessage.error(res.message)
  } catch (e) { if (e !== 'cancel') ElMessage.error('删除失败') }
}

// 权限
async function showPerms(u) {
  permUser.value = u; showPermModal.value = true
  selectedVarietyId.value = ''; grantExpireDate.value = ''
  catGrantCategory.value = ''; catGrantExpire.value = ''
  pushQuotaMsg.value = ''; pushQuotaVal.value = 1
  try {
    const res = await getUserPermissions(u.id)
    userPerms.value = res.code === 200 ? res.data || [] : []
  } catch (e) { userPerms.value = [] }
  // 读取该用户推送额度（仅 USER 有效）
  try {
    const pr = await adminGetPushConfig(u.id)
    if (pr.code === 200 && pr.data) pushQuotaVal.value = Number(pr.data.pushQuota || 1)
  } catch (e) { /* 忽略 */ }
}

async function savePushQuota() {
  if (!permUser.value) return
  pushQuotaSaving.value = true; pushQuotaMsg.value = ''
  try {
    const res = await adminSetPushQuota(permUser.value.id, Number(pushQuotaVal.value))
    if (res.code === 200) { pushQuotaMsg.value = '已保存，商品额度 ' + Number(pushQuotaVal.value) + ' 条' }
    else { pushQuotaMsg.value = res.message || '保存失败' }
  } catch (e) { pushQuotaMsg.value = '保存失败' }
  finally { pushQuotaSaving.value = false }
}

async function grantPerm() {
  if (!selectedVarietyId.value || !permUser.value) return
  try {
    const commodity = allCommodities.value.find(c => String(c.varietiesId) === String(selectedVarietyId.value))
    const vid = Number(selectedVarietyId.value)
    const uid = Number(permUser.value.id)
    const res = await grantPermission(uid, {
      varietiesId: vid,
      varietiesName: commodity ? commodity.name : '',
      expireDate: grantExpireDate.value || ''
    })
    if (res.code === 200) { await showPerms(permUser.value); await loadUsers(); ElMessage.success('授权成功'); grantExpireDate.value = '' }
    else ElMessage.warning(res.message)
  } catch (e) { console.error('授权错误:', e); ElMessage.error('授权失败: ' + (e.message || '')) }
}

// 按大类一键开通
async function grantCatPerm() {
  if (!catGrantCategory.value || !permUser.value) return
  const cat = catGrantCategory.value
  try {
    const uid = Number(permUser.value.id)
    const res = await grantCategoryPermission(uid, {
      category: cat,
      expireDate: catGrantExpire.value || ''
    })
    if (res.code === 200) {
      await showPerms(permUser.value); await loadUsers()
      ElMessage.success(res.message || `已开通「${cat}」`)
      catGrantCategory.value = ''; catGrantExpire.value = ''
    } else ElMessage.warning(res.message)
  } catch (e) { console.error(e); ElMessage.error('开通失败: ' + (e.message || '')) }
}

// 取消某大类的全部授权
async function revokeCatPerm(cat) {
  try {
    await ElMessageBox.confirm(`确认取消「${cat}」大类下所有商品的授权？`, '取消大类授权', { type: 'warning' })
    const res = await revokeCategoryPermission(permUser.value.id, cat)
    if (res.code === 200) { await showPerms(permUser.value); await loadUsers(); ElMessage.success(res.message || '已取消') }
    else ElMessage.warning(res.message)
  } catch (e) { if (e !== 'cancel') ElMessage.error('取消失败') }
}

async function revokePerm(p) {
  try {
    await ElMessageBox.confirm(`确认取消授权「${p.varieties_name}」？`, '取消授权', { type: 'warning' })
    await revokePermission(permUser.value.id, p.id)
    await showPerms(permUser.value); await loadUsers()
    ElMessage.success('取消授权成功')
  } catch (e) { if (e !== 'cancel') ElMessage.error('取消授权失败') }
}

// 日志：行内"日志"按钮 = 打开统一日志弹窗，按该用户精确过滤
async function viewLogs(u) {
  logFilter.value = { type: '', userId: u.id, username: u.username, dateRange: null }
  showAllLogs.value = true
  await loadAllLogs(1)
}

// 清除用户过滤，回到全部日志视图
async function clearUserFilter() {
  logFilter.value = { type: '', userId: null, username: '', dateRange: null }
  await loadAllLogs(1)
}

// ===== 日志展示辅助：action 代码 → 中文描述 / 大类元信息 / 级别徽标 =====
const ACTION_LABEL = {
  LOGIN_SUCCESS: '登录成功', LOGIN_FAIL: '登录失败', LOGIN_LOCKED: '账号冻结', REGISTER_SUCCESS: '注册账号',
  CREATE_USER: '创建用户', UPDATE_USER: '修改用户', DELETE_USER: '删除用户', SET_EXPORT_PERMISSION: '设置导出权限', SET_ENTERPRISE_MAP: '设置标点地图权限',
  GRANT_PERMISSION: '授权商品', REVOKE_PERMISSION: '取消商品授权',
  GRANT_PERMISSION_CATEGORY: '按类目授权', REVOKE_PERMISSION_CATEGORY: '取消类目授权',
  SET_PUSH_QUOTA: '调整推送额度',
  EXPORT_PRICE: '导出价格数据', EXPORT_PRICE_DENIED: '导出被拒绝',
  PUSH_TASK_CREATE: '创建推送任务', PUSH_TASK_UPDATE: '修改推送任务', PUSH_TASK_DELETE: '删除推送任务', PUSH_TASK_TEST: '测试推送任务',
  PUSH_CONFIG_UPDATE: '更新推送设置', PUSH_SEND_TEST: '测试推送邮件', PUSH_SEND: '系统定时推送',
  PUSH_ITEM_GC: '清理失效推送产品', AI_CHAT: 'AI 智能问答',
  CHEM_LOOKUP: '查询物性资料', OPENAPI_CALL: '调用开放 API',
  // 供需广场
  PUBLISH_DEMAND: '发布供需信息', UPDATE_DEMAND: '修改供需信息', DELETE_DEMAND: '删除供需信息',
  VIEW_DEMAND_CONTACT: '查看供需联系方式',
  ADMIN_OFFLINE_DEMAND: '管理员下架供需', ADMIN_DELETE_DEMAND: '管理员删除供需',
  SET_DEMAND_NOTICE: '修改风险提示文案', NOTICE_SEND: '发布公告通知',
  // 企业认证
  SUPPLIER_APPLY: '提交企业认证', SUPPLIER_APPROVE: '企业认证通过', SUPPLIER_REJECT: '企业认证驳回',
  SUPPLIER_REVOKE: '撤销企业认证', SUPPLIER_REQ_REVERIFY: '要求重新认证', UPLOAD_LICENSE: '上传营业执照',
  RESET_LICENSE_UPLOADS: '重置执照上传次数',
  // 实名认证
  REALNAME_IDCARD: '实名二要素核验', REALNAME_PAY: '实名认证支付', REALNAME_CONFIRM: '确认实名信息',
  BIND_PHONE: '绑定手机号', RESET_REALNAME_TRIES: '重置实名尝试次数',
  // 导出
  EXPORT_AI_DENIED: 'AI 导出被拒绝'
}
const TYPE_META = {
  AUTH: { label: '登录认证', cls: 't-auth' },
  DATA: { label: '数据导出', cls: 't-data' },
  PERMISSION: { label: '权限管理', cls: 't-perm' },
  ADMIN: { label: '账号管理', cls: 't-admin' },
  BUSINESS: { label: '推送业务', cls: 't-biz' },
  // 这两个是「业务对象」维度（后端按 target_type 过滤），不是 operation_type
  AI: { label: 'AI 问答', cls: 't-ai' },
  DEMAND: { label: '供需广场', cls: 't-demand' },
  // 物性查询 / 开放 API：按 operation_type 过滤
  CHEM: { label: '物性查询', cls: 't-chem' },
  OPENAPI: { label: '开放 API', cls: 't-openapi' }
}
const TYPE_OPTIONS = ['', 'AUTH', 'DATA', 'PERMISSION', 'ADMIN', 'BUSINESS', 'AI', 'DEMAND', 'CHEM', 'OPENAPI'].map(k => ({ value: k, label: k ? (TYPE_META[k]?.label || k) : '全部类型' }))
const LEVEL_LABEL = { INFO: '信息', WARN: '警告', ERROR: '错误' }

function actionLabel(a) { return ACTION_LABEL[a] || a }
function typeMeta(t) { return TYPE_META[t] || { label: t || '其他', cls: 't-other' } }
/**
 * 日志分类：AI 问答 / 供需广场记在 targetType 上（operationType 统一是 BUSINESS），
 * 展示时优先用 targetType，这样圆点颜色能把这两类和「推送业务」区分开。
 * ⚠️ 字段名是驼峰（接口返回 operationType/targetType），原先写成 operation_type 取不到值 → 圆点永远是灰色。
 */
const TARGET_TYPE_CATS = ['AI', 'DEMAND']
function logCat(log) {
  if (!log) return ''
  return TARGET_TYPE_CATS.includes(log.targetType) ? log.targetType : log.operationType
}
function levelLabel(l) { return LEVEL_LABEL[l] || l || '信息' }

function levelBadge(level) {
  if (level === 'WARN') return 'badge-a'
  if (level === 'ERROR') return 'badge-r'
  return 'badge-i'
}

async function loadAllLogs(page = 1) {
  logPage.value = page
  try {
    const params = { page, size: logPageSize }
    if (logFilter.value.type) params.type = logFilter.value.type
    if (logFilter.value.userId) params.userId = logFilter.value.userId
    else if (logFilter.value.username) params.username = logFilter.value.username
    if (logFilter.value.dateRange && logFilter.value.dateRange.length === 2) {
      params.startDate = logFilter.value.dateRange[0]
      params.endDate = logFilter.value.dateRange[1]
    }
    const res = await getAuditLogs(params)
    if (res.code === 200) {
      allLogs.value = res.data?.list || []
      allLogsTotal.value = res.data?.total || 0
      logPages.value = Math.max(1, Math.ceil(allLogsTotal.value / logPageSize))
    }
  } catch (e) { allLogs.value = []; allLogsTotal.value = 0 }
}

// 重置筛选条件（保留当前用户过滤视角）
function resetLogFilter() {
  logFilter.value = { type: '', userId: logFilter.value.userId, username: logFilter.value.userId ? logFilter.value.username : '', dateRange: null }
  loadAllLogs(1)
}

// 顶部"操作日志"按钮：打开全部日志（无用户过滤）
async function openAllLogs() {
  logFilter.value = { type: '', userId: null, username: '', dateRange: null }
  showAllLogs.value = true
  await loadAllLogs(1)
}

// ===== 按「最后活跃」排序（前端排序：用户列表一次性全量返回，无需请求后端）=====
const activeSort = ref(null)   // null=默认 | 'desc'=最近活跃在前 | 'asc'=最久未活跃在前

/** 取最后活跃时间戳；从未活跃返回 null */
function activeTs(u) {
  const t = u && u.lastActiveAt
  if (!t) return null
  const n = Date.parse(String(t).replace(' ', 'T'))
  return isNaN(n) ? null : n
}

const sortedUsers = computed(() => {
  const arr = [...users.value]
  if (!activeSort.value) return arr
  const dir = activeSort.value === 'desc' ? -1 : 1
  return arr.sort((a, b) => {
    const ta = activeTs(a)
    const tb = activeTs(b)
    // 「从未使用」的账号始终沉底：否则按"最久未活跃"排会顶上一堆废号，看不到真正想找的
    if (ta === null && tb === null) return 0
    if (ta === null) return 1
    if (tb === null) return -1
    return (ta - tb) * dir
  })
})

// 状态筛选：列表现在含已禁用账号（15 个），需要能快速分开看
const statusFilter = ref('all')          // all | on | off
const STATUS_TABS = [
  { k: 'all', t: '全部' },
  { k: 'on', t: '启用' },
  { k: 'off', t: '禁用' }
]
const displayUsers = computed(() => {
  const arr = sortedUsers.value
  if (statusFilter.value === 'on') return arr.filter(u => u.status === 1)
  if (statusFilter.value === 'off') return arr.filter(u => u.status === 0)
  return arr
})
function tabCount(k) {
  const arr = users.value
  if (k === 'on') return arr.filter(u => u.status === 1).length
  if (k === 'off') return arr.filter(u => u.status === 0).length
  return arr.length
}

function toggleActiveSort() {
  activeSort.value = activeSort.value === null ? 'desc'
    : (activeSort.value === 'desc' ? 'asc' : null)
}

const sortActive = computed(() => activeSort.value !== null)
const sortIcon = computed(() => activeSort.value === 'desc' ? '▼'
  : (activeSort.value === 'asc' ? '▲' : '⇅'))
const sortTip = computed(() => {
  if (activeSort.value === 'desc') return '当前：最近活跃在前（点击切换为「最久未活跃在前」）'
  if (activeSort.value === 'asc') return '当前：最久未活跃在前（点击恢复默认顺序）'
  return '点击按「最后活跃」排序（最近活跃在前）'
})

async function loadUsers() {
  try { const res = await getUsers(); if (res.code === 200) users.value = res.data || [] } catch (e) { console.error(e) }
}

onMounted(async () => {
  await loadAnn()
  await loadUsers()
  try { const res = await getCommodities(); if (res.code === 200) allCommodities.value = res.data || [] } catch (e) {}
})

let ctx
onMounted(() => { ctx = gsap.context(() => { gsap.fromTo('.user-manage > *', { y: 10, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.4, ease: 'power3.out', stagger: 0.05 }) }) })
onUnmounted(() => { ctx && ctx.revert() })
</script>

<style scoped>
.user-manage { padding: 0; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { font-size: 18px; font-weight: 600; }
.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; border-radius: 8px; background: #09090b; color: #fff; border: none; font-size: 13px; font-weight: 500; cursor: pointer; font-family: inherit; }
.btn-primary:hover { opacity: .9; }
.btn-primary:disabled { opacity: .5; }
.btn-log {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 16px; border-radius: 8px;
  background: rgba(255,255,255,.6);
  border: 1px solid #e4e4e7; color: #3f3f46;
  font-size: 13px; font-weight: 500; cursor: pointer; font-family: inherit;
  box-shadow: 0 1px 2px rgba(31,41,55,.04);
  transition: all .2s;
}
.btn-log:hover { background: #fff; border-color: #cbd5e1; color: #09090b; box-shadow: 0 2px 8px rgba(31,41,55,.08); }
.btn-log:active { transform: translateY(1px); }
.btn-sm { padding: 6px 12px; font-size: 12px; }
.btn-cancel { padding: 8px 16px; border-radius: 8px; background: #f4f4f5; color: #71717a; border: 1px solid #e4e4e7; font-size: 13px; cursor: pointer; font-family: inherit; }
.card {
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 12px; overflow: hidden;
}
/* 用户表列较多（10 列），窄屏时允许横向滚动，避免右列被 overflow:hidden 直接裁掉 */
.card-users { overflow-x: auto; }

/* 状态筛选条（列表含禁用账号后新增） */
.um-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 10px 14px; border-bottom: 1px solid #f1f5f9; flex-wrap: wrap;
}
.um-tabs { display: inline-flex; align-items: center; gap: 2px; padding: 2px; background: #f4f4f5; border-radius: 9px; }
.um-tab {
  border: 0; background: transparent; cursor: pointer; font-family: inherit;
  font-size: 12px; color: #52525b; padding: 6px 12px; border-radius: 7px;
  transition: all .15s; white-space: nowrap;
}
.um-tab:hover { color: #18181b; }
.um-tab.on { background: #fff; color: #111827; font-weight: 600; box-shadow: 0 1px 2px rgba(0,0,0,.06); }
.um-tab b { margin-left: 5px; font-weight: 600; color: #94a3b8; }
.um-tab.on b { color: #2563eb; }
.um-note { font-size: 11.5px; color: #b45309; }

/* 禁用行整体淡化，但状态列与操作列保持清晰 */
.row-off td { opacity: .5; }
.row-off td:nth-child(5), .row-off td.actions { opacity: 1; }

/* ===== 公告管理 ===== */
.ann-card { margin-bottom: 0; padding: 0; }
.ann-head { font-size: 14px; font-weight: 700; color: #111827; margin-bottom: 10px; }
.ann-sub { font-size: 12px; font-weight: 400; color: #9ca3af; margin-left: 8px; }
.ann-pub { display: flex; gap: 10px; align-items: flex-start; }
.ann-cur { display: flex; align-items: center; gap: 10px; margin-top: 10px; padding: 8px 10px; background: #fffbeb; border: 1px solid #fde68a; border-radius: 8px; font-size: 13px; }
.ann-none { color: #9ca3af; background: #f9fafb; border-color: #e5e7eb; }
.ann-cur-tag { flex: none; background: #f59e0b; color: #fff; border-radius: 4px; padding: 1px 8px; font-size: 12px; }
.ann-cur-text { flex: 1; min-width: 0; color: #92400e; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ann-cur-time { flex: none; color: #9ca3af; font-size: 12px; }
.ann-history { margin-top: 10px; }
.ann-h-title { font-size: 12px; color: #6b7280; margin-bottom: 6px; }
.ann-h-row { display: flex; align-items: center; gap: 8px; padding: 4px 0; font-size: 13px; color: #374151; }
.ann-h-dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: #d1d5db; }
.ann-h-dot.on { background: #22c55e; }
.ann-h-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ann-h-time { flex: none; color: #9ca3af; font-size: 12px; font-variant-numeric: tabular-nums; cursor: default; }

/* ===== 公告管理 v2 ===== */
.ann-editing { margin-left: 8px; font-size: 11.5px; font-weight: 600; color: #1d4ed8;
  background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 999px; padding: 1px 9px; }
.ann-ed-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.ann-ed-row .ann-lbl + .ann-levels { margin-right: 8px; }
.ann-dp { width: 340px !important; }
@media (max-width: 900px) { .ann-dp { width: 100% !important; } }
.ann-lbl { font-size: 12px; color: #6b7280; flex: none; width: 32px; }
.ann-levels { display: flex; gap: 6px; }
.ann-lv { font-size: 12px; border-radius: 6px; padding: 3px 12px; cursor: pointer;
  border: 1px solid #e4e4e7; background: #fafafa; color: #52525b; transition: all .15s; }
.ann-lv.normal.on     { background: #eff6ff; border-color: #93c5fd; color: #1d4ed8; font-weight: 600; }
.ann-lv.important.on  { background: #fff7ed; border-color: #fdba74; color: #c2410c; font-weight: 600; }
.ann-lv.maintenance.on{ background: #f5f3ff; border-color: #c7d2fe; color: #6d28d9; font-weight: 600; }
.ann-pub { display: flex; gap: 10px; align-items: flex-start; }
.ann-btns { display: flex; flex-direction: column; gap: 6px; flex: none; }

/* 走马灯预览 */
.ann-preview { display: flex; align-items: center; gap: 8px; margin-top: 10px;
  padding: 7px 12px; border-radius: 8px; border: 1px solid #e2e8f0; background: #f8fafc; }
.ann-preview .ann-pv-horn { flex: none; font-size: 13px; }
.ann-preview .ann-pv-text { flex: 1; min-width: 0; font-size: 12.5px; color: #475569;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ann-preview.normal      { background: #f8fafc; border-color: #e2e8f0; }
.ann-preview.important   { background: #fff7ed; border-color: #fdba74; }
.ann-preview.important .ann-pv-text { color: #c2410c; font-weight: 600; }
.ann-preview.maintenance { background: #f5f3ff; border-color: #c7d2fe; }
.ann-preview.maintenance .ann-pv-text { color: #6d28d9; font-weight: 600; }

/* 当前生效：按级别配色 */
.ann-cur.important   { background: #fff7ed; border-color: #fdba74; }
.ann-cur.important .ann-cur-tag { background: #ea580c; }
.ann-cur.important .ann-cur-text { color: #9a3412; }
.ann-cur.maintenance { background: #f5f3ff; border-color: #c7d2fe; }
.ann-cur.maintenance .ann-cur-tag { background: #7c3aed; }
.ann-cur.maintenance .ann-cur-text { color: #5b21b6; }

/* 工具条 */
.ann-tools { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.ann-tools .ann-h-title { margin: 0; font-weight: 600; color: #374151; }
.ann-kw { width: 200px; }
.ann-total { font-size: 12px; color: #9ca3af; margin-left: auto; }
.ann-empty { font-size: 12.5px; color: #9ca3af; padding: 10px 0; text-align: center; }

/* 行 */
.ann-h-row { display: flex; align-items: center; gap: 8px; padding: 5px 0; font-size: 13px;
  color: #374151; border-bottom: 1px solid #f4f4f5; }
.ann-h-row.cur { background: #fafbfc; }
.ann-lv-tag { flex: none; font-size: 10.5px; border-radius: 4px; padding: 1px 6px;
  background: #f1f5f9; color: #64748b; }
.ann-lv-tag.important   { background: #fff7ed; color: #c2410c; }
.ann-lv-tag.maintenance { background: #f5f3ff; color: #6d28d9; }
.ann-h-ops { display: flex; gap: 2px; flex: none; }
.ann-op { background: none; border: none; font-size: 12px; color: #2563eb; cursor: pointer;
  padding: 2px 6px; border-radius: 4px; }
.ann-op:hover { background: #eff6ff; }
.ann-op.danger { color: #dc2626; }
.ann-op.danger:hover { background: #fef2f2; }
.ann-op.primary { color: #15803d; }
.ann-op.primary:hover { background: #f0fdf4; }
.ann-pager { display: flex; justify-content: center; margin-top: 10px; }

/* 卡头「发布公告」按钮 */
.ann-new-btn {
  margin-left: auto; display: inline-flex; align-items: center; gap: 5px;
  background: #2f6bff; color: #fff; border: none; border-radius: 7px;
  font-size: 12.5px; font-weight: 600; padding: 6px 14px; cursor: pointer; transition: all .16s;
}
.ann-new-btn:hover { background: #1e4fd8; }
.ann-head { display: flex; align-items: center; }
.ann-head .ann-sub { margin-left: 0; }
/* 入口按钮上的「有生效公告」提示点 */
.ann-live-dot { flex: none; width: 7px; height: 7px; border-radius: 50%; background: #f59e0b;
  box-shadow: 0 0 0 3px rgba(245, 158, 11, .18); margin-left: 2px; }
.btn-ann-entry { position: relative; }

/* 弹窗内布局 */
.ann-dlg .ann-ed-row { margin-bottom: 12px; }
.ann-dlg .ann-preview { margin-top: 12px; }
.ann-tip { margin-top: 10px; font-size: 11.5px; color: #9ca3af; line-height: 1.7; }
.ann-tip strong { color: #6b7280; }
.table { width: 100%; min-width: 1080px; border-collapse: collapse; font-size: 12.5px; }
.table th { text-align: left; padding: 9px 8px; font-weight: 500; font-size: 11px; color: #71717a; background: #f4f4f5; border-bottom: 1px solid #e4e4e7; text-transform: uppercase; letter-spacing: .3px; }
.table td { padding: 9px 8px; border-bottom: 1px solid #f4f4f5; white-space: nowrap; }
.table td.wrap-ok { white-space: normal; }
/* 可排序表头 */
.th-sort { cursor: pointer; user-select: none; white-space: nowrap; transition: color .15s; }
.th-sort:hover { color: #2563eb; }
.th-sort.on { color: #2563eb; font-weight: 600; }
.th-sort .sort-ar { font-size: 9px; margin-left: 3px; opacity: .5; }
.th-sort.on .sort-ar { opacity: 1; }

.cell-t { color: var(--ink3, #52525b); font-variant-numeric: tabular-nums; white-space: nowrap; }
.cell-mail { max-width: 140px; overflow: hidden; text-overflow: ellipsis; }
.table tbody tr:hover { background: #fafafa; }
/* 操作列吸附右侧：表格横向滚动时仍可点到「编辑/权限/日志/删除」 */
.table th:last-child,
.table td:last-child {
  position: sticky; right: 0; z-index: 2;
  box-shadow: -8px 0 8px -8px rgba(15, 23, 42, .10);
}
.table th:last-child { background: #f4f4f5; }
.table td:last-child { background: #fff; }
.table tbody tr:hover td:last-child { background: #fafafa; }

.empty-row { text-align: center; color: #71717a; padding: 40px !important; }
.badge { padding: 2px 7px; border-radius: 6px; font-size: 10.5px; font-weight: 500; display: inline-block; }
.badge-m { background: #dbeafe; color: #1d4ed8; }
.badge-r { background: #fee2e2; color: #dc2626; }
/* 供应商认证徽章（跟在角色后面，同列展示） */
.sup-ok { background: #dcfce7; color: #15803d; margin-left: 4px; }
.sup-wait { background: #fef3c7; color: #92400e; margin-left: 4px; }
.badge-g { background: #dcfce7; color: #15803d; }
.badge-a { background: #fffbeb; color: #b45309; }
.badge-i { background: #e0e7ff; color: #4338ca; }
.badge-e { background: #ffedd5; color: #c2410c; }
.badge-fl { background: #f4f4f5; color: #71717a; }
.badge-test { background: #fef3c7; color: #b45309; margin-left: 4px; }
.actions { white-space: nowrap; }
.action-link { color: #3b82f6; font-size: 12px; cursor: pointer; margin-right: 8px; }
.action-link:hover { text-decoration: underline; }
.action-link.danger { color: #ef4444; }
.perm-tag { display: inline-block; padding: 2px 8px; margin: 2px; border-radius: 4px; font-size: 11px; background: #eff6ff; color: #2563eb; border: 1px solid #bfdbfe; }
.perm-all { display: inline-block; padding: 3px 10px; background: rgba(99,102,241,.1); color: #4f46e5; border-radius: 6px; font-size: 12px; font-weight: 600; }
.perm-empty { color: #71717a; font-size: 12px; }
.perm-summary-link { display: inline-flex; align-items: center; gap: 6px; padding: 4px 8px; border-radius: 6px; transition: background .15s; cursor: default; white-space: nowrap; }
.perm-summary-link:hover { background: rgba(99,102,241,.06); }
.perm-pill { padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.perm-pill-cat { background: #eef2ff; color: #4f46e5; }
.perm-pill-item { background: #f0fdf4; color: #15803d; }
.perm-pill-sep { color: #71717a; font-size: 10px; }
.perm-hint-mini { color: #71717a; font-size: 10px; margin-left: 2px; white-space: nowrap; }
.perm-popover-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; max-height: 360px; overflow-y: auto; padding: 4px; }
.perm-popover-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 5px 8px; background: #fafafa; border-radius: 5px; }
.perm-popover-row .perm-cat-tag { margin: 0; }
.perm-popover-cnt { font-size: 11px; color: #71717a; white-space: nowrap; }
.perm-cell { display: inline-flex; flex-wrap: wrap; gap: 4px; align-items: center; max-width: 380px; }
.perm-cat-tag { display: inline-block; padding: 2px 8px; margin: 2px 0; border-radius: 4px; font-size: 11px; font-weight: 600; background: #f0fdf4; color: #15803d; border: 1px solid #bbf7d0; white-space: nowrap; }
.perm-total { font-size: 11px; color: #71717a; margin-left: 2px; }
.perm-sec-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.perm-sec-head h4 { font-size: 13px; font-weight: 600; margin: 0; }
.perm-summary { font-size: 11px; color: #71717a; }
.perm-groups { display: flex; flex-direction: column; gap: 10px; max-height: 300px; overflow-y: auto; }
.perm-group { border: 1px solid #f0f0f3; border-radius: 8px; padding: 8px 10px; }
.perm-group-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.perm-cat-count { font-size: 11px; color: #71717a; }
.perm-group .perm-revoke { margin-left: auto; }
.pagination-info { font-size: 12px; color: #71717a; }
.page-btn { width: 32px; height: 32px; border: 1px solid #e4e4e7; border-radius: 6px; background: var(--card); font-size: 12px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.page-btn:disabled { opacity: .4; cursor: not-allowed; }
/* ---- 标点地图有效期（列表列 + 表单提示） ---- */
.cell-emap { white-space: nowrap; }
.cell-emap .emap-exp { display: block; font-size: 11px; color: var(--ink4, #a1a1aa); margin-top: 2px; }
.cell-emap .emap-exp.exp-soon { color: #d97706; font-weight: 600; }
.cell-emap .emap-exp.exp-over { color: #dc2626; font-weight: 600; }
.emap-na { color: var(--ink4, #a1a1aa); font-size: 12px; }
.badge.emap-on { background: #dcfce7; color: #15803d; }
.badge.emap-over { background: #fee2e2; color: #b91c1c; }
.date-inp { height: 34px; padding: 0 10px; border: 1px solid var(--line, #e4e4e7); border-radius: 6px;
  font-size: 13px; font-family: inherit; background: var(--card, #fff); color: inherit; }
.form-hint { font-size: 12px; color: var(--ink3, #71717a); margin: 6px 0 0; line-height: 1.6; }
.form-hint.hint-warn { color: #dc2626; font-weight: 600; }
.perm-expire { font-size: 11px; color: #71717a; margin: 0 4px; }
.perm-expire.expire-soon { color: #f59e0b; font-weight: 600; }
.perm-expire.expire-over { color: #ef4444; font-weight: 600; }
.perm-revoke { margin-left: 6px; font-size: 11px; color: #ef4444; background: none; border: none; cursor: pointer; }
.perm-revoke:hover { text-decoration: underline; }
.perm-hint { font-size: 12px; color: #71717a; margin-bottom: 16px; }
.perm-section { margin-bottom: 16px; }
.perm-section h4 { font-size: 13px; font-weight: 600; margin-bottom: 8px; }
.perm-list { display: flex; flex-wrap: wrap; gap: 6px; }
.perm-item { display: flex; align-items: center; }
.perm-empty { font-size: 12px; color: #71717a; padding: 12px 0; }
.perm-add-row { display: flex; gap: 8px; align-items: center; }
.perm-select { flex: 1; padding: 8px 12px; border: 1px solid #e4e4e7; border-radius: 8px; font-size: 13px; font-family: inherit; }
.log-user-banner { display: flex; align-items: center; justify-content: space-between; margin-top: 12px; padding: 8px 12px; background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 8px; font-size: 12px; color: #1d4ed8; }
.btn-clear-filter { padding: 4px 10px; border-radius: 6px; background: #dbeafe; color: #1d4ed8; border: none; font-size: 12px; cursor: pointer; font-family: inherit; }
.btn-clear-filter:hover { background: #bfdbfe; }
/* ===== 操作日志弹窗 ===== */
.modal-box.log-modal {
  width: min(1180px, 96vw);
  height: min(88vh, 960px);
  max-height: none;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 26px 30px;
}
.modal-box.log-modal > *:not(.log-scroll) { flex-shrink: 0; }
.log-head { display: flex; align-items: center; justify-content: space-between; }
.log-head h3 { margin: 0; font-size: 18px; font-weight: 600; }
.log-head .log-count-sub { font-size: 12px; color: #71717a; font-weight: 400; margin-left: 10px; }
.log-toolbar { display: flex; gap: 10px; margin-top: 18px; flex-wrap: wrap; align-items: center; }
.log-user-banner { margin-top: 14px; }
.log-scroll { flex: 1 1 auto; overflow-y: auto; margin-top: 16px; min-height: 200px; border: 1px solid #eef0f3; border-radius: 12px; background: #fff; }
.log-scroll .log-table { margin-top: 0; }
.log-scroll .log-table thead th { position: sticky; top: 0; z-index: 2; background: #f6f6f8; }
.log-table th { white-space: nowrap; padding: 13px 16px; font-size: 12px; letter-spacing: .4px; }
.log-table td { vertical-align: top; padding: 14px 16px; }
.log-table .cell-time { color: #6b7280; font-family: 'JetBrains Mono', Consolas, monospace; font-size: 12.5px; white-space: nowrap; }
.log-table .cell-detail { color: #374151; font-size: 13px; line-height: 1.65; min-width: 280px; }
.log-table .cell-ip { color: #71717a; font-size: 12.5px; }
.log-modal .badge { padding: 3px 10px; font-size: 12px; border-radius: 7px; }
.sys-tag { display: inline-block; padding: 2px 9px; border-radius: 6px; background: #f1f5f9; color: #475569; font-size: 11.5px; font-weight: 600; border: 1px solid #e2e8f0; }
.log-type-dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; margin-right: 7px; vertical-align: middle; }
.t-auth { background: #8b5cf6; } .t-data { background: #3b82f6; } .t-perm { background: #f59e0b; }
.t-admin { background: #64748b; } .t-biz { background: #10b981; } .t-other { background: #d1d5db; }
.t-ai { background: #06b6d4; } .t-demand { background: #f43f5e; }
.t-chem { background: #0ea5e9; } .t-openapi { background: #7c3aed; }
.log-act { font-weight: 600; color: #111827; font-size: 13px; }
.log-pager { display: flex; justify-content: space-between; align-items: center; margin-top: 16px; }
.log-modal .form-actions { margin-top: 18px; }
.modal-box {
  background: rgba(255,255,255,.82);
  backdrop-filter: blur(20px) saturate(160%);
  -webkit-backdrop-filter: blur(20px) saturate(160%);
  border: 1px solid rgba(255,255,255,.7);
  border-radius: 16px; padding: 28px; width: 420px; max-height: 85vh; overflow-y: auto;
  box-shadow: 0 20px 60px rgba(0,0,0,0.2);
}
.modal-wide { width: 640px; }
.modal-box h3 { font-size: 16px; font-weight: 600; margin-bottom: 12px; }
.form-group { margin-bottom: 14px; }
.form-group label { display: block; font-size: 12px; font-weight: 500; color: #71717a; margin-bottom: 6px; }
.form-group input, .form-group select { width: 100%; padding: 9px 12px; border: 1px solid #e4e4e7; border-radius: 8px; font-size: 13px; font-family: inherit; outline: none; }
.form-group input:focus, .form-group select:focus { border-color: #3b82f6; }
.form-group input:disabled { background: #f9fafb; color: #6b7280; }
.form-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
.form-error { color: #ef4444; font-size: 12px; margin-top: 10px; }

/* ===== 用户详情弹窗信息区 ===== */
.ud-info {
  background: #f8fafc; border: 1px solid #eef2f7; border-radius: 10px;
  padding: 12px 14px; margin-bottom: 14px;
  display: grid; grid-template-columns: 1fr 1fr; gap: 8px 18px;
}
.ud-row { display: flex; align-items: flex-start; gap: 8px; font-size: 12.5px; min-width: 0; }
.ud-row > span { width: 62px; flex: none; color: #94a3b8; line-height: 1.7; }
.ud-row b { font-weight: 600; color: #1f2937; min-width: 0; line-height: 1.7; }
/* 长文本完整展示：换行不截断（企业名、IP、邮箱等） */
.ud-row b.ud-wrap { white-space: normal; word-break: break-word; overflow-wrap: anywhere; }
/* 实名身份证号：等宽字体便于核对，含历史数据说明 */
.ud-row b.ud-idc { font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  letter-spacing: .6px; font-size: 12.5px; }
.ud-row b.ud-idc .ud-idc-note { display: block; font-family: inherit; font-style: normal;
  font-weight: 400; color: #94a3b8; font-size: 11px; letter-spacing: 0; margin-top: 2px; }
.ud-row b.ud-wrap .ud-ip { display: block; font-style: normal; font-weight: 400; color: #94a3b8; font-size: 11.5px; margin-top: 2px; }
.ud-row .ud-co { font-style: normal; color: #64748b; font-weight: 400; margin-left: 4px; word-break: break-word; }
.ud-perm-btn {
  margin-left: auto; border: none; background: none; cursor: pointer;
  color: #2f6bff; font-size: 12px; font-family: inherit; padding: 0;
}
.ud-perm-btn:hover { text-decoration: underline; }


/* 用户详情弹窗加宽 */
.modal-box.modal-user { width: 620px; }


/* 重置按钮行 */
.ud-actions { display: flex; gap: 10px; margin-bottom: 14px; }
.ud-reset {
  border: 1px solid #e4e4e7; background: #fff; border-radius: 7px;
  font-size: 12px; color: #52525b; padding: 5px 10px; cursor: pointer; font-family: inherit;
}
.ud-reset:hover { border-color: #fca5a5; color: #b91c1c; }
/* AI 功能开关面板 */
.ai-flag-row { display: flex; align-items: flex-start; gap: 16px; padding: 6px 2px; }
.ai-flag-info { flex: 1; min-width: 0; }
.ai-flag-name { font-size: 13.5px; font-weight: 600; color: #1f2937; }
.ai-flag-desc { font-size: 12px; color: #71717a; margin-top: 5px; line-height: 1.65;
  white-space: normal; word-break: break-word; }
.ud-reset.ud-danger { color: #b91c1c; border-color: #fecaca; background: #fef2f2; }
.ud-reset.ud-danger:hover { background: #fee2e2; border-color: #fca5a5; }

</style>
