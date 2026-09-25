<template>
  <div class="sd-page">
    <!-- 页头 -->
    <div class="pg-hd">
      <div class="pg-l">
        <h3 class="pg-t">供需广场</h3>
        <span class="pg-tag">求购需求 · 供应货源</span>
      </div>
      <div class="pg-r">
        <!-- 求购消息通知：认证企业可自助开关；未认证显示锁定入口（页头快捷入口） -->
        <span v-if="notify.eligible" class="nt-notify" :class="{ on: notify.enabled }" :title="notify.tip">
          <el-switch v-model="notify.enabled" size="small" :loading="notify.saving"
                     :disabled="notify.saving" @change="onNotifyChange" />
          <span class="nt-notify-t">接收求购消息</span>
        </span>
        <button v-else-if="notify.checked" type="button" class="btn ghost nt-notify-lock"
                title="企业认证通过后可开启求购消息通知（有新求购会发到你的邮箱）"
                @click="$router.push('/supplier-verify')">
          <Lock :size="12" /> 接收求购消息
        </button>
        <!-- 工商核验入口：与供应商审核页一致，方便核验发布方企业信息 -->
        <a class="gsxt-link" href="https://www.gsxt.gov.cn/" target="_blank" rel="noopener"
           title="国家企业信用信息公示系统，核验发布方企业信息">
          核验企业 ↗
        </a>
        <button v-if="isAdmin" class="btn ghost" @click="openNoticeEdit">
          <Settings :size="13" /> 提示设置
        </button>
        <button class="btn primary" @click="openPublish()">
          <Plus :size="14" /> 发布供需信息
        </button>
      </div>
    </div>

    <!-- 联系方式可见性提示 -->
    <div class="sd-tip" :class="supplierExpired ? 'warn' : (canViewFull ? 'ok' : (realname ? 'ok' : 'lock'))">
      <TriangleAlert v-if="supplierExpired" :size="14" />
      <Lock v-else-if="!canViewFull && !realname" :size="14" />
      <CircleCheck v-else :size="14" />
      <span v-if="canViewFull">你已是认证供应商，可查看全部完整联系方式（每日上限 50 条，查看会留痕）</span>
      <span v-else-if="supplierExpired">
        <b>你的企业认证已过期</b>，需重新提交认证后才能发布信息、查看完整联系方式。
        <a class="sd-link" @click="$router.push('/supplier-verify')">立即重新认证 →</a>
      </span>
      <span v-else-if="realname">
        你已实名，可查看<b>企业发布</b>的完整联系方式（<span class="sd-warn">个人发布的不对外开放</span>）。
        <a class="sd-link" @click="$router.push('/supplier-verify')">认证企业可看全部 →</a>
      </span>
      <span v-else>
        联系方式已脱敏显示。<b>完成实名（免费）即可查看企业发布的联系方式</b>；个人发布的不对外开放。
        <a class="sd-link" @click="$router.push('/supplier-verify')">去实名 →</a>
      </span>
    </div>

    <!-- ===== 搜索区 ===== -->
    <div class="cd sd-search">
      <div class="ss-bar">
        <Search :size="17" class="ss-ic" />
        <input v-model="q.keyword" class="ss-input"
               placeholder="搜索标题、产品、公司名称或详细说明…"
               @keyup.enter="searchNow" />
        <span v-if="q.keyword" class="ss-clr" title="清空" @click="q.keyword = ''; searchNow()">×</span>
        <button class="ss-btn" @click="searchNow">搜索</button>
      </div>
      <div class="ss-adv">
        <el-select v-model="q.varietiesNames" multiple filterable collapse-tags
                   :max-collapse-tags="2" placeholder="全部产品（可多选）"
                   class="sd-sel w-240" @change="searchNow">
          <el-option-group v-for="g in commodityGroups" :key="g.category" :label="g.category">
            <el-option v-for="c in g.items" :key="c.id" :label="c.name" :value="c.name" />
          </el-option-group>
        </el-select>
        <button class="btn ghost sd-refresh" :disabled="refreshing" @click="refreshData">
          <RefreshCw :size="13" :class="{ spin: refreshing }" />
          {{ refreshing ? '刷新中…' : '刷新' }}
        </button>
        <button v-if="hasFilter" class="btn ghost" @click="resetQ">清空筛选</button>
        <button class="btn ghost" @click="openMine">我的发布</button>
        <span class="ss-sum" v-if="hasFilter">共命中 {{ demand.total + supply.total }} 条</span>
      </div>
    </div>

    <!-- ===== 两个板块：求购 / 供应 分开 ===== -->
    <section v-for="sec in SECTIONS" :key="sec.key" class="sd-sec">
      <div class="sec-hd">
        <span class="sec-dot" :class="sec.key"></span>
        <span class="sec-t">{{ sec.label }}</span>
        <span class="sec-desc">{{ sec.desc }}</span>
        <span class="sec-c">共 <b>{{ st[sec.key].total }}</b> 条</span>
        <span class="sec-line"></span>
        <div class="sec-pager" v-if="st[sec.key].total > pageSize">
          <button class="pg-nav" :disabled="st[sec.key].page <= 1" title="上一页"
                  @click="loadOne(sec.key, st[sec.key].page - 1)">
            <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor"
                 stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>
            上一页
          </button>
          <span class="pg-nums">
            <template v-for="(p, pi) in pageList(sec.key)" :key="sec.key + '-' + pi">
              <span v-if="p === '...'" class="pg-gap">…</span>
              <button v-else class="pg-num" :class="{ on: p === st[sec.key].page }"
                      :title="'第 ' + p + ' 页'" @click="loadOne(sec.key, p)">{{ p }}</button>
            </template>
          </span>
          <button class="pg-nav" :disabled="st[sec.key].page >= maxPage(sec.key)" title="下一页"
                  @click="loadOne(sec.key, st[sec.key].page + 1)">
            下一页
            <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor"
                 stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>
          </button>
        </div>
      </div>

      <div v-if="st[sec.key].loading" class="sd-empty">正在加载…</div>
      <div v-else-if="!st[sec.key].list.length" class="sd-empty slim">
        <Inbox :size="26" class="sd-empty-ico" />
        <p>{{ hasFilter ? '没有符合筛选条件的' + sec.label : '暂无' + sec.label }}</p>
        <p class="dim">
          {{ hasFilter ? '换个筛选条件试试，或点「清空筛选」。' : '点右上角「发布供需信息」发一条。' }}
        </p>
      </div>
      <div v-else class="sd-grid">
        <div v-for="it in st[sec.key].list" :key="it.id" class="sd-card" @click="openDetail(it)">
          <div class="sd-top">
            <!-- 类型徽章：同屏区分求购 / 供应 -->
            <span class="sd-kind" :class="it.type">{{ it.type === 'supply' ? '供应' : '求购' }}</span>
            <span class="sd-title" :title="it.title">{{ it.title }}</span>
            <span v-if="urgentDays(it.expire_at)" class="sd-urgent" title="临近有效期">
              <Flame :size="10" />急
            </span>
            <!-- 管理员可治理他人发布的内容 -->
            <span v-if="isAdmin && !it.mine" class="sd-mng">
              <span class="sd-mng-b" title="下架该信息" @click.stop="doAdminOffline(it.id)">下架</span>
              <span class="sd-mng-b del" title="删除该信息" @click.stop="doAdminDelete(it.id)">删除</span>
            </span>
          </div>

          <div class="sd-meta">
            <!-- 品种名：最多 3 个 chip + 「+N」，完整列表看悬浮提示 -->
            <span v-for="(vn, vi) in vnChips(it.varieties_name).list" :key="'vn' + vi"
                  class="sd-vn-chip" :title="it.varieties_name">{{ vn }}</span>
            <span v-if="vnChips(it.varieties_name).more" class="sd-vn-more"
                  :title="it.varieties_name">+{{ vnChips(it.varieties_name).more }}</span>
          </div>

          <!-- 数据胶囊：期望价 / 数量 / 规格 / 地区 -->
          <div class="sd-figs">
            <span class="sd-fig price" :class="{ na: !it.expect_price }">
              <i>期望价</i>{{ it.expect_price ? fmtNum(it.expect_price) : '面议' }}<em v-if="it.expect_price">元/{{ it.unit || '吨' }}</em>
            </span>
            <span v-if="it.quantity" class="sd-fig qty">
              <i>数量</i>{{ fmtNum(it.quantity) }}<em>{{ it.unit || '吨' }}</em>
            </span>
            <span v-if="it.spec" class="sd-fig"><i>规格</i>{{ it.spec }}</span>
            <span v-if="it.region" class="sd-fig"><MapPin :size="10" />{{ it.region }}</span>
          </div>
          <div class="sd-remark" v-if="it.remark">{{ it.remark }}</div>

          <div class="sd-co" v-if="it.company_name || it.enterprise || it.supplier_level === 1">
            <Medal :size="12" class="sd-co-ic" />
            <span class="sd-co-n biz-link" title="查看企业名片" @click.stop="openCard(it)">{{ it.company_name || it.supplier_company || '已认证企业' }}</span>
            <span class="sd-badge">已认证</span>
          </div>

          <div class="sd-foot">
            <span class="sd-ct">
              <Phone :size="11" />{{ it.contact_phone || '—' }}
              <span class="sd-sep">·</span>
              {{ it.contact_email || '—' }}
            </span>
            <span class="sd-time">{{ relTime(it.created_at) }}</span>
          </div>

          <!-- 元信息行：浏览数 + 剩余有效期 -->
          <div class="sd-stats">
            <span class="sd-st"><Eye :size="10" />{{ it.view_count || 0 }}</span>
            <span v-if="daysLeft(it.expire_at) !== null" class="sd-st"
                  :class="{ warn: daysLeft(it.expire_at) <= 3, dead: daysLeft(it.expire_at) <= 0 }">
              <Clock :size="10" />{{ daysLeft(it.expire_at) > 0 ? '剩 ' + daysLeft(it.expire_at) + ' 天' : '已到期' }}
            </span>
          </div>
        </div>
      </div>
    </section>

    <!-- ============ 企业名片 ============ -->
    <el-dialog v-model="cardDlg" title="企业名片" width="400px">
      <div class="biz-card" v-if="cardInfo">
        <div class="biz-hd">
          <Medal :size="18" class="biz-medal" />
          <div class="biz-co">{{ cardInfo.companyName || (cardInfo.companyHidden ? '公司全称未公开' : '—') }}</div>
          <span class="biz-badge" v-if="cardInfo.verified">已认证企业</span>
        </div>
        <div class="biz-reg" v-if="cardInfo.regStatus">
          <span>工商登记状态</span><b>{{ cardInfo.regStatus }}</b>
          <em v-if="cardInfo.regCheckedAt">{{ cardInfo.regCheckedAt }} 查询</em>
        </div>
        <div class="biz-grid">
          <div class="biz-kv" v-if="cardInfo.creditCode"><span>统一社会信用代码</span><b>{{ cardInfo.creditCode }}</b></div>
          <div class="biz-kv"><span>联系人</span><b>{{ cardInfo.contactName || '—' }}</b></div>
          <div class="biz-kv"><span>手机号</span><b :class="{ masked: !cardInfo.full }">{{ cardInfo.contactPhone || '—' }}</b></div>
          <div class="biz-kv"><span>邮箱</span><b :class="{ masked: !cardInfo.full }">{{ cardInfo.contactEmail || '—' }}</b></div>
          <div class="biz-kv" v-if="cardInfo.validUntil"><span>认证有效期</span><b>至 {{ cardInfo.validUntil }}</b></div>
        </div>
        <div class="biz-ft" v-if="!cardInfo.full">公司全称与联系方式均未公开 —— 完成实名 / 企业认证后，点「查看完整联系方式」即可看到</div>
      </div>
    </el-dialog>

    <!-- ============ 发布弹窗 ============ -->
    <el-dialog v-model="dlgPub" :title="editId ? (editRepublish ? '修改供需信息（保存后重新上架）' : '修改供需信息')
               : (pubForm.type === 'supply' ? '发布供应信息' : '发布求购需求')"
               width="900px" :close-on-click-modal="false" @closed="resetPub">
      <div class="pb-form">
        <div v-if="editRepublish" class="repub-tip">
          该信息当前为「已下架 / 已过期」，保存后将自动重新上架，有效期顺延 30 天。
        </div>
        <div class="pb-type">
          <label class="pb-type-opt" :class="{ on: pubForm.type === 'demand' }">
            <input type="radio" value="demand" v-model="pubForm.type" @change="onTypeChange" />
            <span class="pb-type-t">求购需求</span>
            <span class="pb-type-d">我要找货 · 需实名 + 手机号</span>
          </label>
          <label class="pb-type-opt" :class="{ on: pubForm.type === 'supply' }">
            <input type="radio" value="supply" v-model="pubForm.type" @change="onTypeChange" />
            <span class="pb-type-t">供应货源</span>
            <span class="pb-type-d">我有货卖 · 需企业认证</span>
          </label>
        </div>

        <div class="pb-sec">货源信息</div>
        <div class="pb-grid">
          <div class="pb-f pb-full">
            <label class="pb-l"><i>*</i>标题</label>
            <input v-model="pubForm.title" class="pb-ipt" maxlength="120"
                   placeholder="如：求购 PP 拉丝料 30 吨 / 供应甲醇 现货" />
          </div>
        </div>

        <!-- 多行供货明细：每行 产品名称 / 数量 / 单位 / 规格 / 期望价，行尾加号新增一行 -->
        <div class="it-head">
          <span class="it-h-name"><i>*</i>产品名称</span>
          <span class="it-h-qty"><i v-if="pubForm.type === 'demand'">*</i>数量</span>
          <span class="it-h-unit">单位</span>
          <span class="it-h-spec">规格</span>
          <span class="it-h-price">期望价</span>
          <span class="it-h-remark">备注</span>
          <span class="it-h-op"></span>
        </div>
        <div class="it-rows">
          <div v-for="(it, idx) in pubForm.items" :key="idx" class="it-row">
            <div class="it-c it-c-name">
              <el-select v-model="it.name" filterable allow-create default-first-option
                         placeholder="选择品种" class="sd-sel w-full">
                <el-option-group v-for="g in commodityGroups" :key="g.category" :label="g.category">
                  <el-option v-for="c in g.items" :key="c.id" :label="c.name" :value="c.name" />
                </el-option-group>
              </el-select>
            </div>
            <div class="it-c">
              <input v-model="it.quantity" class="pb-ipt" placeholder="30" />
            </div>
            <div class="it-c">
              <select v-model="it.unit" class="pb-ipt">
                <option v-for="u in UNITS" :key="u" :value="u">{{ u }}</option>
              </select>
            </div>
            <div class="it-c">
              <input v-model="it.spec" class="pb-ipt" placeholder="选填" />
            </div>
            <div class="it-c">
              <input v-model="it.expectPrice" class="pb-ipt" placeholder="选填" />
            </div>
            <div class="it-c it-c-remark">
              <input v-model="it.remark" class="pb-ipt" maxlength="100" placeholder="选填" />
            </div>
            <div class="it-c it-c-op">
              <button type="button" class="it-add" title="新增一行" @click="addItem()">＋</button>
              <button v-if="pubForm.items.length > 1" type="button" class="it-del"
                      title="删除这一行" @click="removeItem(idx)">－</button>
            </div>
          </div>
        </div>
        <div class="pb-h">每一行一个品种，可随时点 ＋ 增行、点 － 删行。<span v-if="pubForm.type === 'demand'">求购需逐行填写数量，便于供应商判断能否接单。</span>列表外的品种：输入名称后，点击下拉里出现的「创建」候选即可添加。</div>

        <div class="pb-sec">联系方式<span class="pb-sec-h">已自动带入账号信息，可修改 · 对外默认脱敏，仅认证供应商可见</span></div>
        <div class="pb-grid2">
          <div class="pb-f">
            <label class="pb-l"><i>*</i>联系人</label>
            <input v-model="pubForm.contactName" class="pb-ipt" placeholder="姓名" />
          </div>
          <div class="pb-f">
            <label class="pb-l"><i>*</i>手机号
              <span v-if="phoneBound" class="pb-bound">已绑定账号</span>
            </label>
            <input v-model="pubForm.contactPhone" class="pb-ipt" maxlength="11"
                   placeholder="未绑定时填写，首次发布后自动绑定" />
          </div>
          <div class="pb-f">
            <label class="pb-l">邮箱</label>
            <input v-model="pubForm.contactEmail" class="pb-ipt" placeholder="选填" />
          </div>
          <div class="pb-f">
            <label class="pb-l">公司名称</label>
            <input v-model="pubForm.companyName" class="pb-ipt"
                   :placeholder="pubForm.type === 'supply' ? '建议填写，便于采购方识别' : '选填，便于供应方识别'" />
          </div>
        </div>

        <div v-if="pubErr" class="fm-err">{{ pubErr }}</div>
        <div class="pb-h">发布后 30 天内有效，到期自动下架；每人每日最多发布 5 条。</div>
      </div>
      <template #footer>
        <button class="btn ghost" @click="dlgPub = false">取消</button>
        <button class="btn primary" :disabled="publishing" @click="doPublish">
          {{ publishing ? '保存中…' : (editId ? (editRepublish ? '保存并重新上架' : '保存修改') : '确认发布') }}
        </button>
      </template>
    </el-dialog>

    <!-- ============ 详情弹窗 ============ -->
    <el-dialog v-model="dlgDetail" title="供需详情" width="760px">
      <div v-if="detail" class="sd-detail">
        <div class="dt-head">
          <span class="sd-kind" :class="detail.type">{{ detail.type === 'demand' ? '求购' : '供应' }}</span>
          <span class="dt-title">{{ detail.title }}</span>
        </div>

        <!-- 概览胶囊：一眼看清这条供需的关键信息 -->
        <div class="dt-tags">
          <span class="sd-fig" v-if="detailItems.length"><i>品种</i>{{ detailItems.length }}<em>个</em></span>
          <span class="sd-fig" v-if="detail.region"><MapPin :size="10" />{{ detail.region }}</span>
          <span class="sd-fig"><i>有效期</i>
            <template v-if="daysLeft(detail.expire_at) !== null">
              {{ daysLeft(detail.expire_at) > 0 ? '剩 ' + daysLeft(detail.expire_at) + ' 天' : '已到期' }}
            </template>
            <template v-else>—</template>
          </span>
          <span class="sd-fig"><Eye :size="10" />{{ detail.view_count || 0 }}<em>次浏览</em></span>
          <span class="sd-fig ok" v-if="detail.company_name || detail.supplier_level === 1">
            <Medal :size="10" />已认证企业
          </span>
        </div>
        <table class="dt-tb" v-if="detailItems.length">
          <thead>
            <tr>
              <th class="dt-n">#</th><th>产品名称</th><th>数量</th><th>单位</th><th>规格</th><th>期望价</th><th>备注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(it, i) in detailItems" :key="i">
              <td class="dt-n">{{ i + 1 }}</td>
              <td class="dt-tb-n"><span class="dt-vn-chip">{{ it.name || '—' }}</span></td>
              <td :class="{ 'dt-na': it.quantity == null || it.quantity === '' }">
                {{ it.quantity != null && it.quantity !== '' ? fmtNum(it.quantity) : '面议' }}</td>
              <td :class="{ 'dt-na': !it.unit }">{{ it.unit || '吨' }}</td>
              <td :class="{ 'dt-na': !it.spec }">{{ it.spec || '—' }}</td>
              <td :class="{ 'dt-na': it.expectPrice == null || it.expectPrice === '' }">
                {{ it.expectPrice != null && it.expectPrice !== ''
                   ? fmtNum(it.expectPrice) + ' 元/' + (it.unit || '吨') : '面议' }}</td>
              <td class="dt-rc" :class="{ 'dt-na': !it.remark }" :title="it.remark || ''">
                {{ it.remark || '—' }}</td>
            </tr>
          </tbody>
        </table>

        <div class="dt-grid" v-if="detail.delivery_date">
          <div class="dt-i"><span class="sd-k">期望交期</span>
            <span>{{ String(detail.delivery_date).slice(0, 10) }}</span></div>
        </div>
        <div class="dt-block" v-if="detail.remark">
          <div class="sd-k">详细说明</div><div class="dt-remark">{{ detail.remark }}</div>
        </div>
        <div class="dt-block" v-if="detail.company_name || detail.supplier_level === 1 || enterprisePost">
          <div class="sd-k">发布方</div>
          <div class="sd-co">
            <Medal :size="12" class="sd-co-ic" />
            <span class="sd-co-n">{{ detail.company_name || detail.supplier_company || '已认证企业' }}</span>
            <span class="sd-badge">已认证</span>
          </div>
        </div>

        <div class="ct-box">
          <div class="ct-head">
            <span>联系方式</span>
            <button v-if="!contact && !isMine && canViewContact" class="btn primary sm"
                    :disabled="loadingContact" @click="revealContact">
              {{ loadingContact ? '获取中…' : '查看完整联系方式' }}
            </button>
            <button v-else-if="!contact && !isMine && enterprisePost" class="btn ghost sm"
                    @click="$router.push('/supplier-verify')">
              <Lock :size="12" /> 实名后可见
            </button>
            <span v-else-if="!contact && !isMine" class="ct-private">个人发布 · 不对外开放</span>
          </div>
          <div class="ct-row"><span class="sd-k">联系人</span>
            <span>{{ contact ? contact.contactName : (detail.contact_name || '—') }}</span></div>
          <div class="ct-row"><span class="sd-k">手机</span>
            <span :class="{ masked: !contact }">{{ contact ? contact.contactPhone : (detail.contact_phone || '—') }}</span></div>
          <div class="ct-row"><span class="sd-k">邮箱</span>
            <span :class="{ masked: !contact }">{{ contact ? contact.contactEmail : (detail.contact_email || '—') }}</span></div>
          <div class="ct-row" v-if="contact && contact.companyName">
            <span class="sd-k">公司</span><span>{{ contact.companyName }}</span></div>
          <div class="ct-note" v-if="contact && contact.dailyLimit">
            今日已查看 {{ contact.usedToday }} / {{ contact.dailyLimit }} 条（每次查看都会记录）
          </div>
          <div class="ct-note" v-else-if="!contact && !canViewContact && !isMine">
            <template v-if="enterprisePost">
              企业发布的联系方式面向已实名用户开放，完成实名（免费）即可查看。
            </template>
            <template v-else>
              该信息由个人发布，完整联系方式不对外开放。
            </template>
          </div>
        </div>
      </div>
      <template #footer>
        <button v-if="isMine" class="btn ghost" @click="doOffline">下架该信息</button>
        <template v-if="isAdmin && !isMine && detail">
          <button class="btn ghost" @click="doAdminOffline(detail.id, true)">下架（管理员）</button>
          <button class="btn danger" @click="doAdminDelete(detail.id, true)">删除（管理员）</button>
        </template>
        <button class="btn ghost" @click="dlgDetail = false">关闭</button>
      </template>
    </el-dialog>

    <!-- ============ 我的发布 ============ -->
    <el-dialog v-model="dlgMine" title="我的发布" width="860px">
      <div v-if="!mineList.length" class="sd-empty">
        <Inbox :size="26" class="sd-empty-ico" />
        <p>你还没有发布过供需信息</p>
        <p class="dim">发布后可在这里管理（编辑 / 下架）。</p>
      </div>
      <div v-else class="sd-tbwrap">
      <table class="sd-tb">
        <thead><tr><th>类型</th><th>标题</th><th>产品</th><th>数量</th><th>状态</th><th>浏览</th><th>发布时间</th><th></th></tr></thead>
        <tbody>
          <tr v-for="m in mineList" :key="m.id">
            <td><span class="sd-type sm" :class="m.type">{{ m.type === 'demand' ? '求购' : '供应' }}</span></td>
            <td class="ell" :title="m.title"><span class="clamp">{{ trunc(m.title, 24) }}</span></td>
            <td class="ell2" :title="m.varieties_name"><span class="clamp">{{ trunc(m.varieties_name, 30) }}</span></td>
            <td>{{ m.quantity ? fmtNum(m.quantity) + (m.unit || '吨') : '—' }}</td>
            <td><span class="sd-st" :class="m.status">{{ ST[m.status] || m.status }}</span></td>
            <td class="num">{{ m.view_count }} / {{ m.contact_view_count }}</td>
            <td class="dim">{{ shortTime(m.created_at) }}</td>
            <td>
              <span v-if="m.status === 'online'" class="sd-act" @click="openEdit(m)">编辑</span>
              <span v-if="m.status === 'offline' || m.status === 'expired'" class="sd-act"
                    title="编辑后自动重新上架（有效期顺延 30 天）" @click="openEdit(m)">重上架</span>
              <span v-if="m.status === 'online'" class="sd-act" @click="doOfflineById(m.id)">下架</span>
              <span v-if="m.status !== 'online'" class="sd-act del" @click="doDeleteMine(m.id)">删除</span>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
    </el-dialog>

    <!-- ============ 风险提示弹窗（进入页面自动弹出） ============ -->
    <el-dialog v-model="dlgNotice" :title="notice.title || '交易风险提示'" width="640px"
               align-center :close-on-click-modal="false" class="notice-dlg">
      <div class="nt-body">{{ notice.content || '—' }}</div>
      <div class="nt-foot">
        <label class="nt-chk">
          <input type="checkbox" v-model="dontShowToday" /> 今天不再提示
        </label>
        <span v-if="isAdmin" class="nt-edit" @click="dlgNotice = false; openNoticeEdit()">编辑提示文案</span>
      </div>
      <template #footer>
        <button class="btn primary" @click="closeNotice">我已知悉</button>
      </template>
    </el-dialog>

    <!-- ============ 提示文案设置（仅管理员） ============ -->
    <el-dialog v-model="dlgNoticeEdit" title="提示文案设置" width="700px" :close-on-click-modal="false">
      <div class="sd-form">
        <div class="fm-row">
          <label class="fm-l">启用提示</label>
          <div class="fm-c">
            <div><el-switch v-model="editNotice.enabled" /></div>
            <div class="fm-h">关闭后，用户进入供需广场不再弹出该提示。</div>
          </div>
        </div>
        <div class="fm-row">
          <label class="fm-l">标题</label>
          <div class="fm-c">
            <input v-model="editNotice.title" class="ipt w-full" maxlength="60" placeholder="如：交易风险提示" />
          </div>
        </div>
        <div class="fm-row">
          <label class="fm-l">正文</label>
          <div class="fm-c">
            <textarea v-model="editNotice.content" class="ipt w-full ta" rows="11" maxlength="2000"
                      placeholder="换行会原样显示"></textarea>
            <div class="fm-h">{{ (editNotice.content || '').length }} / 2000 字</div>
          </div>
        </div>
        <div v-if="noticeErr" class="fm-err">{{ noticeErr }}</div>
      </div>
      <template #footer>
        <button class="btn ghost" @click="previewNotice">预览效果</button>
        <button class="btn ghost" @click="dlgNoticeEdit = false">取消</button>
        <button class="btn primary" :disabled="noticeSaving" @click="saveNotice">
          {{ noticeSaving ? '保存中…' : '保存' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus, Search, Lock, CircleCheck, TriangleAlert, MapPin, Phone, Medal, Settings, Inbox, RefreshCw, Eye, Clock, Flame } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { getAccount, getDemandList, getDemandOptions, getDemandDetail, getDemandContact,
         publishDemand, offlineDemand, getMyDemands,
         getDemandNotice, getAdminDemandNotice, saveAdminDemandNotice, getDemandCard,
         updateDemand, getSupplyStatus, deleteDemand,
         getDemandNotify, saveDemandNotify } from '../api/index'

const authStore = useAuthStore()
const isAdmin = computed(() => authStore.user?.role === 'ADMIN')

// 两个板块：求购 / 供应 分开展示，各自独立翻页
const SECTIONS = [
  { key: 'demand', label: '求购需求', desc: '有人要买' },
  { key: 'supply', label: '供应货源', desc: '有人要卖' }
]
const UNITS = ['吨', '公斤', '千克', '桶', '件', '台', '立方米']
const ST = { online: '展示中', offline: '已下架', expired: '已过期', pending: '待审核', rejected: '已驳回' }

const pageSize = 6
const st = reactive({
  demand: { list: [], total: 0, page: 1, loading: false },
  supply: { list: [], total: 0, page: 1, loading: false }
})
const demand = computed(() => st.demand)
const supply = computed(() => st.supply)

const canViewFull = ref(false)
const supplierExpired = ref(false)

// 详情页的供货明细：优先 items，老帖子兜底成单行
/** 品种总数 */
function vnTotal(name) {
  return String(name || '').split(/[、,，;；]/).map(x => x.trim()).filter(Boolean).length
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前（超过 30 天回退绝对时间） */
function relTime(t) {
  if (!t) return '—'
  const d = new Date(String(t).replace(' ', 'T'))
  if (isNaN(d.getTime())) return shortTime(t)
  const min = Math.floor((Date.now() - d.getTime()) / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return min + ' 分钟前'
  const hr = Math.floor(min / 60)
  if (hr < 24) return hr + ' 小时前'
  const day = Math.floor(hr / 24)
  if (day < 30) return day + ' 天前'
  return shortTime(t)
}

/** 距到期天数（null = 无到期时间；<=0 表示已到期） */
function daysLeft(t) {
  if (!t) return null
  const d = new Date(String(t).replace(' ', 'T'))
  if (isNaN(d.getTime())) return null
  return Math.ceil((d.getTime() - Date.now()) / 86400000)
}

/** 是否临近到期（<=3 天且未过期）—— 卡片上打「急」标 */
function urgentDays(t) {
  const n = daysLeft(t)
  return n !== null && n > 0 && n <= 3
}

/** 把「A、B、C…」品种串拆成 chip 列表：最多 3 个 + 剩余计数（卡片用） */
const VN_MAX = 3
function trunc(s, n) {
  const t = String(s == null ? '' : s)
  return t.length > n ? t.slice(0, n) + '…' : t
}

function vnChips(name) {
  const arr = String(name || '').split(/[、,，;；]/).map(x => x.trim()).filter(Boolean)
  if (!arr.length) return { list: [], more: 0 }
  return { list: arr.slice(0, VN_MAX), more: Math.max(0, arr.length - VN_MAX) }
}

const detailItems = computed(() => {
  const d = detail.value
  if (!d) return []
  if (Array.isArray(d.items) && d.items.length) return d.items
  if (!d.varieties_name) return []
  return [{
    name: d.varieties_name,
    quantity: d.quantity,
    unit: d.unit || '吨',
    spec: d.spec,
    expectPrice: d.expect_price,
    remark: d.remark
  }]
})
const opts = reactive({ commodities: [] })
const q = ref({ varietiesNames: [], keyword: '' })
const hasFilter = computed(() =>
  q.value.varietiesNames.length > 0 || !!q.value.keyword)

const maxPage = (k) => Math.max(1, Math.ceil(st[k].total / pageSize))

/**
 * 页码序列：页数少时全列出；页数多时收成「1 … 当前±1 … 末页」。
 * 返回数组里可能含字符串 '...' 作为省略号占位。
 */
function pageList(k) {
  const total = maxPage(k)
  const cur = st[k].page
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const out = [1]
  const from = Math.max(2, cur - 1)
  const to = Math.min(total - 1, cur + 1)
  if (from > 2) out.push('...')
  for (let i = from; i <= to; i++) out.push(i)
  if (to < total - 1) out.push('...')
  out.push(total)
  return out
}

// 产品按分类分组，找起来快
const commodityGroups = computed(() => {
  const map = new Map()
  for (const c of opts.commodities) {
    const k = c.category || '其他'
    if (!map.has(k)) map.set(k, [])
    map.get(k).push(c)
  }
  return [...map.entries()].map(([category, items]) => ({ category, items }))
})

function fmtNum(v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  return Number.isNaN(n) ? String(v) : n.toLocaleString('en-US')
}
function shortTime(t) { return t ? String(t).replace('T', ' ').slice(5, 16) : '—' }

function baseParams(type) {
  return {
    type,
    size: pageSize,
    varietiesNames: q.value.varietiesNames.length ? q.value.varietiesNames.join(',') : undefined,
    keyword: q.value.keyword || undefined
  }
}

/** 只加载一个板块 */
async function loadOne(key, p) {
  const s = st[key]
  if (p) s.page = p
  s.loading = true
  try {
    const res = await getDemandList({ ...baseParams(key), page: s.page })
    if (res.code === 200 && res.data) {
      s.list = res.data.list || []
      s.total = res.data.total || 0
      canViewFull.value = !!res.data.canViewFullContact
    }
  } catch (e) { ElMessage.error('加载失败，请稍后重试') }
  finally { s.loading = false }
}

/** 两个板块一起刷新（改筛选条件时用） */
function loadAll() {
  st.demand.page = 1
  st.supply.page = 1
  return Promise.all([loadOne('demand', 1), loadOne('supply', 1)])
}

function searchNow() { loadAll() }

/** 手动刷新：重拉两个板块（保留当前筛选与页码）+ 重读风险提示文案 */
const refreshing = ref(false)
async function refreshData() {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await Promise.all([loadOne('demand', st.demand.page), loadOne('supply', st.supply.page)])
    try { await loadNotice() } catch (e) { /* 提示文案拉取失败不影响列表刷新 */ }
    ElMessage.success('已刷新')
  } finally {
    refreshing.value = false
  }
}
function resetQ() {
  q.value = { varietiesNames: [], keyword: '' }
  loadAll()
}

// ---------------- 发布 ----------------
const dlgPub = ref(false)
const publishing = ref(false)
const pubErr = ref('')
const emptyItem = () => ({ name: '', quantity: '', unit: '吨', spec: '', expectPrice: '', remark: '' })
const emptyPub = () => ({ type: 'demand', title: '', items: [emptyItem()],
  contactName: '', contactPhone: '', contactEmail: '', companyName: '' })

// 供货明细增删行
function addItem() { pubForm.value.items.push(emptyItem()) }
function removeItem(idx) {
  if (pubForm.value.items.length <= 1) return
  pubForm.value.items.splice(idx, 1)
}
const pubForm = ref(emptyPub())

// ===== 已绑定手机号（发布时自动带出） =====
const acctPhone = ref('')
// ---------------- 求购消息通知开关（页头快捷入口） ----------------
// 门槛：企业认证通过（后端计费/权限以此为准）；管理员可自测
const notify = ref({ checked: false, eligible: false, enabled: false, email: '', saving: false,
                     tip: '开启后，平台有新求购会发邮件通知你' })

async function loadNotify() {
  if (!authStore.token) return
  try {
    const res = await getDemandNotify()
    const d = res && res.data ? res.data : (res || {})
    notify.value.checked = true
    notify.value.eligible = !!d.eligible
    notify.value.enabled = !!d.enabled
    notify.value.email = d.email || d.accountEmail || ''
    notify.value.tip = notify.value.enabled
      ? ('求购消息将发送到 ' + (notify.value.email || '你的账号邮箱'))
      : '开启后，平台有新求购会发邮件通知你'
  } catch (e) { /* 未登录或接口异常时静默隐藏 */ }
}

async function saveNotify(enabled) {
  notify.value.saving = true
  try {
    // 关闭时也把邮箱带上，避免"关了再开"丢掉自定义邮箱
    const res = await saveDemandNotify({ enabled, email: notify.value.email })
    const d = res && res.data ? res.data : (res || {})
    notify.value.enabled = !!d.enabled
    if (d.email) notify.value.email = d.email
    notify.value.tip = notify.value.enabled
      ? ('求购消息将发送到 ' + (notify.value.email || '你的账号邮箱'))
      : '开启后，平台有新求购会发邮件通知你'
    ElMessage.success(notify.value.enabled
      ? ('已开启：有新求购会发到 ' + (notify.value.email || '你的账号邮箱'))
      : '已关闭：不再接收求购消息')
  } catch (e) {
    notify.value.enabled = !enabled   // 失败把开关拨回去
    ElMessage.error((e && (e.message || e.msg)) || '保存失败，请稍后重试')
  } finally {
    notify.value.saving = false
  }
}

async function onNotifyChange(val) {
  // 开启前必须有收件邮箱：没有就让用户填一个（写入 demand_notify_email）
  if (val && !notify.value.email) {
    try {
      const r = await ElMessageBox.prompt('请填写接收求购消息的邮箱', '接收邮箱', {
        confirmButtonText: '保存并开启', cancelButtonText: '取消',
        inputPattern: /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/,
        inputErrorMessage: '邮箱格式不正确'
      })
      notify.value.email = (r && r.value || '').trim()
    } catch (e) {
      notify.value.enabled = false
      return
    }
  }
  await saveNotify(!!val)
}

onMounted(async () => {
  try {
    const res = await getAccount()
    const d = res && res.data ? res.data : res
    if (d && d.phoneBound) acctPhone.value = d.phone || ''
    meAcct.value = d || {}
  } catch (e) { /* 忽略 */ }
  loadNotify()
})

const meAcct = ref({})
// 已保存的默认联系方式（上次发布实际填写的），发布表单优先带出
const memDefault = ref({ name: '', phone: '', email: '' })
const cardDlg = ref(false)
const cardInfo = ref(null)
const phoneBound = computed(() => !!meAcct.value.phoneBound)

async function openCard(it) {
  try {
    const res = await getDemandCard(it.id)
    cardInfo.value = res && res.data ? res.data : res
    cardDlg.value = true
  } catch (e) { ElMessage.error('名片加载失败') }
}

function openPublish() {
  editId.value = null
  dlgPub.value = true
  // 联系方式自动带出：优先「上次发布实际填写的」，其次账号资料（仅当字段为空，用户可改）
  const a = meAcct.value || {}
  const md = memDefault.value || {}
  if (!pubForm.value.contactName) pubForm.value.contactName = md.name || a.nickname || ''
  if (!pubForm.value.contactPhone) pubForm.value.contactPhone = md.phone || a.phone || ''
  if (!pubForm.value.contactEmail) pubForm.value.contactEmail = md.email || a.email || ''
  if (!pubForm.value.companyName && a.supplierCompany) pubForm.value.companyName = a.supplierCompany
}
function resetPub() { pubForm.value = emptyPub(); pubErr.value = ''; editRepublish.value = false }
// 切换求购/供应时清掉上一次的校验提示（两类必填项不同，留着会误导）
function onTypeChange() { pubErr.value = '' }

// ---------------- 编辑自己的发布 ----------------
const editId = ref(null)
const editRepublish = ref(false)   // 编辑已下架/已过期帖：保存后自动重新上架

/** 把一条供需记录还原成编辑表单（items 全量回填；老帖无 items 时按品种名拆成多行，避免丢品种） */
function buildEditForm(rec) {
  const raw = rec || {}
  let items = []
  if (Array.isArray(raw.items) && raw.items.length) {
    items = raw.items.map(x => ({
      name: x.name || '',
      quantity: x.quantity != null ? String(x.quantity) : '',
      unit: x.unit || '吨',
      spec: x.spec || '',
      expectPrice: x.expectPrice != null ? String(x.expectPrice) : '',
      remark: x.remark || ''
    }))
  } else {
    // 老数据没有 items：用 varieties_name 拆行还原，至少不丢品种
    const names = String(raw.varieties_name || '').split(/[、,，/|]/).map(v => v.trim()).filter(Boolean)
    const qty = raw.quantity != null ? String(raw.quantity) : ''
    const price = raw.expect_price != null ? String(raw.expect_price) : ''
    items = names.map((n, i) => ({
      name: n,
      quantity: i === 0 ? qty : '',
      unit: raw.unit || '吨',
      spec: i === 0 ? (raw.spec || '') : '',
      expectPrice: i === 0 ? price : '',
      remark: i === 0 ? (raw.remark || '') : ''
    }))
  }
  if (!items.length) items = [emptyItem()]
  return {
    type: raw.type || 'demand',
    title: raw.title || '',
    items,
    contactName: raw.contact_name || '',
    contactPhone: raw.contact_phone || '',
    contactEmail: raw.contact_email || '',
    companyName: raw.company_name || ''
  }
}

async function openEdit(m) {
  editId.value = m.id
  editRepublish.value = m.status !== 'online'
  // 关键：不能只用「我的发布」列表的数据（列表不含 items，会把多行明细压成一行）
  // 一律回源详情接口，拿完整记录再回填
  let rec = m
  try {
    const res = await getDemandDetail(m.id)
    const post = res && res.code === 200 && res.data ? res.data.post : null
    if (post) {
      rec = post
      // 兜底：万一详情里是脱敏值（139****1111），用「我的发布」列表里的原文，避免保存被校验拦
      if (typeof rec.contact_phone === 'string' && rec.contact_phone.includes('*') && m.contact_phone) {
        rec = { ...rec, contact_phone: m.contact_phone }
      }
      if (typeof rec.contact_email === 'string' && rec.contact_email.includes('*') && m.contact_email) {
        rec = { ...rec, contact_email: m.contact_email }
      }
    }
  } catch (e) { /* 详情拉取失败时退回列表数据 */ }
  pubForm.value = buildEditForm(rec)
  dlgMine.value = false
  dlgPub.value = true
}

async function doPublish() {
  pubErr.value = ''
  const f = pubForm.value
  if (!f.title.trim()) { pubErr.value = '请填写标题'; return }
  // 明细行：过滤掉没填品种的空行
  const items = (f.items || []).filter(it => String(it.name || '').trim())
  if (!items.length) { pubErr.value = '请至少填写一行产品名称'; return }
  // 求购：每行数量必填（供应商据此判断能否接单）；供应：数量选填
  // 数量必须是数字或留空
  for (let i = 0; i < items.length; i++) {
    const qv = String(items[i].quantity || '').trim()
    if (f.type === 'demand' && !qv) {
      pubErr.value = items.length > 1
        ? '求购需要填写数量：第 ' + (i + 1) + ' 行「' + String(items[i].name).trim() + '」缺少数量'
        : '求购需要填写数量，请补充数量后再提交'
      return
    }
    if (qv && isNaN(Number(qv))) {
      pubErr.value = '第 ' + (i + 1) + ' 行的数量请填写数字'
      return
    }
    const pv = String(items[i].expectPrice || '').trim()
    if (pv && isNaN(Number(pv))) {
      pubErr.value = '第 ' + (i + 1) + ' 行的期望价请填写数字'
      return
    }
  }
  if (!f.contactName.trim()) { pubErr.value = '请填写联系人'; return }
  if (!/^1[3-9]\d{9}$/.test(f.contactPhone.trim())) { pubErr.value = '请填写正确的 11 位手机号'; return }
  publishing.value = true
  try {
    const clean = items.map(it => ({
      name: String(it.name).trim(),
      quantity: String(it.quantity || '').trim(),
      unit: it.unit || '吨',
      spec: String(it.spec || '').trim(),
      expectPrice: String(it.expectPrice || '').trim(),
      remark: String(it.remark || '').trim()
    }))
    const names = clean.map(it => it.name)
    const hit = opts.commodities.find(c => c.name === names[0])
    const payload = {
      type: f.type,
      title: f.title,
      items: clean,
      contactName: f.contactName,
      contactPhone: f.contactPhone,
      contactEmail: f.contactEmail,
      companyName: f.companyName,
      varietiesName: names.join('、'),
      varietiesId: hit ? hit.id : null
    }
    const res = editId.value ? await updateDemand(editId.value, payload)
                             : await publishDemand(payload)
    if (res.code === 200) {
      ElMessage.success(editId.value
        ? (res.data && res.data.republished ? '已保存并重新上架' : '修改已保存')
        : '发布成功')
      dlgPub.value = false
      editId.value = null
      editRepublish.value = false
      loadAll(); loadMine()
    } else {
      pubErr.value = res.message || '保存失败'
    }
  } catch (e) {
    pubErr.value = e?.response?.data?.message || '发布失败，请稍后重试'
  } finally { publishing.value = false }
}

// ---------------- 详情 ----------------
const dlgDetail = ref(false)
const detail = ref(null)
const contact = ref(null)
const loadingContact = ref(false)
const isMine = ref(false)
// 逐帖可见性（后端 canSeeContact 判定）：实名用户对企业帖可见、个人帖不可见
const canViewContact = ref(false)
const enterprisePost = ref(false)
// 当前用户是否已实名（顶部提示条用）
const realname = ref(false)

async function openDetail(it) {
  contact.value = null
  try {
    const res = await getDemandDetail(it.id)
    if (res.code === 200 && res.data) {
      detail.value = res.data.post
      canViewFull.value = !!res.data.canViewFullContact
      isMine.value = !!res.data.mine
      canViewContact.value = !!res.data.canViewContact
      enterprisePost.value = !!res.data.enterprisePost
      dlgDetail.value = true
    } else ElMessage.error(res.message || '加载失败')
  } catch (e) { ElMessage.error('加载失败，请稍后重试') }
}

async function revealContact() {
  loadingContact.value = true
  try {
    const res = await getDemandContact(detail.value.id)
    if (res.code === 200) contact.value = res.data
    else ElMessage.warning(res.message || '无法查看')
  } catch (e) {
    ElMessage.warning(e?.response?.data?.message || '无法查看联系方式')
  } finally { loadingContact.value = false }
}

async function doOffline() {
  if (!detail.value) return
  await doOfflineById(detail.value.id)
  dlgDetail.value = false
}

async function doOfflineById(id) {
  try {
    const res = await offlineDemand(id)
    if (res.code === 200) { ElMessage.success('已下架'); loadAll(); loadMine() }
    else ElMessage.error(res.message || '操作失败')
  } catch (e) { ElMessage.error('操作失败') }
}

/** 删除自己的发布（不可恢复，仅非展示中状态显示入口） */
async function doDeleteMine(id) {
  try {
    await ElMessageBox.confirm('删除后不可恢复，该条供需信息将被彻底移除。确定删除？',
      '删除我的发布', { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'error' })
  } catch (e) { return }   // 用户取消
  try {
    const res = await deleteDemand(id)
    if (res.code === 200) { ElMessage.success('已删除'); loadMine(); loadAll() }
    else ElMessage.error(res.message || '删除失败')
  } catch (e) { ElMessage.error('删除失败') }
}

// ---------------- 管理员内容治理（下架 / 删除他人的发布） ----------------

/** 管理员下架（可逆，对方可编辑后重新上架） */
async function doAdminOffline(id, closeAfter) {
  try {
    await ElMessageBox.confirm(
      '下架后该信息将不再在广场展示（对方可编辑后重新上架）。确定下架？',
      '管理员下架', { confirmButtonText: '确定下架', cancelButtonText: '取消', type: 'warning' })
  } catch (e) { return }   // 用户取消
  try {
    const res = await offlineDemand(id)
    if (res.code === 200) {
      ElMessage.success('已下架')
      if (closeAfter) dlgDetail.value = false
      loadAll(); loadMine()
    } else ElMessage.error(res.message || '操作失败')
  } catch (e) { ElMessage.error('操作失败') }
}

/** 管理员删除（不可恢复） */
async function doAdminDelete(id, closeAfter) {
  try {
    await ElMessageBox.confirm(
      '删除后不可恢复，该条供需信息将被彻底移除。如需保留痕迹请改用「下架」。确定删除？',
      '管理员删除', { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'error' })
  } catch (e) { return }   // 用户取消
  try {
    const res = await deleteDemand(id)
    if (res.code === 200) {
      ElMessage.success('已删除')
      if (closeAfter) dlgDetail.value = false
      loadAll(); loadMine()
    } else ElMessage.error(res.message || '操作失败')
  } catch (e) { ElMessage.error('操作失败') }
}

// ---------------- 风险提示 ----------------
const NOTICE_LS = 'cm_supply_notice_dismiss'
const notice = ref({ enabled: true, title: '', content: '' })
const dlgNotice = ref(false)
const dontShowToday = ref(false)

function todayStr() {
  const d = new Date()
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0')
    + '-' + String(d.getDate()).padStart(2, '0')
}
function noticeSig(n) { return (n.title || '') + '|' + (n.content || '').slice(0, 80) }

function shouldShowNotice(n) {
  try {
    const raw = JSON.parse(localStorage.getItem(NOTICE_LS) || 'null')
    if (!raw) return true
    // 同一天 + 文案没变过 → 不再弹；管理员改了文案会自动重新弹
    return !(raw.date === todayStr() && raw.sig === noticeSig(n))
  } catch (e) { return true }
}

function closeNotice() {
  if (dontShowToday.value) {
    try {
      localStorage.setItem(NOTICE_LS, JSON.stringify({ date: todayStr(), sig: noticeSig(notice.value) }))
    } catch (e) { /* 隐私模式下忽略 */ }
  }
  dlgNotice.value = false
}

async function loadNotice() {
  try {
    const res = await getDemandNotice()
    if (res.code === 200 && res.data) {
      notice.value = res.data
      if (res.data.enabled && shouldShowNotice(res.data)) {
        dontShowToday.value = false
        dlgNotice.value = true
      }
    }
  } catch (e) { /* 静默：拿不到提示不影响页面 */ }
}

// ---------------- 提示文案设置（仅管理员） ----------------
const dlgNoticeEdit = ref(false)
const noticeSaving = ref(false)
const noticeErr = ref('')
const editNotice = ref({ enabled: true, title: '', content: '' })

async function openNoticeEdit() {
  noticeErr.value = ''
  try {
    const res = await getAdminDemandNotice()
    const d = (res.code === 200 && res.data) ? res.data : notice.value
    editNotice.value = { enabled: !!d.enabled, title: d.title || '', content: d.content || '' }
  } catch (e) {
    editNotice.value = { enabled: !!notice.value.enabled, title: notice.value.title || '', content: notice.value.content || '' }
  }
  dlgNoticeEdit.value = true
}

function previewNotice() {
  if (!editNotice.value.title.trim()) { noticeErr.value = '请填写标题'; return }
  noticeErr.value = ''
  notice.value = { ...editNotice.value }
  dontShowToday.value = false
  dlgNotice.value = true
}

async function saveNotice() {
  noticeErr.value = ''
  if (!editNotice.value.title.trim()) { noticeErr.value = '请填写标题'; return }
  if (!editNotice.value.content.trim()) { noticeErr.value = '请填写正文'; return }
  noticeSaving.value = true
  try {
    const res = await saveAdminDemandNotice({ ...editNotice.value })
    if (res.code === 200) {
      ElMessage.success(res.data || '已保存')
      notice.value = { ...editNotice.value }
      dlgNoticeEdit.value = false
    } else noticeErr.value = res.message || '保存失败'
  } catch (e) {
    noticeErr.value = e?.response?.data?.message || '保存失败，请稍后重试'
  } finally { noticeSaving.value = false }
}

// ---------------- 我的发布 ----------------
const dlgMine = ref(false)
const mineList = ref([])
async function loadMine() {
  try {
    const res = await getMyDemands()
    if (res.code === 200) mineList.value = res.data || []
  } catch (e) { /* 静默 */ }
}
function openMine() { loadMine(); dlgMine.value = true }

onMounted(async () => {
  try {
    const res = await getDemandOptions()
    if (res.code === 200 && res.data) {
      opts.commodities = res.data.commodities || []
    }
  } catch (e) { /* 静默 */ }
  try {
    const sr = await getSupplyStatus()
    const sd = (sr && sr.data) ? sr.data : sr
    if (sd) {
      if (typeof sd.canViewFull === 'boolean') canViewFull.value = sd.canViewFull
      supplierExpired.value = !!sd.supplierExpired
      realname.value = !!sd.realname
      if (sd.defaultContact) {
        memDefault.value = {
          name: sd.defaultContact.name || '',
          phone: sd.defaultContact.phone || '',
          email: sd.defaultContact.email || ''
        }
      }
    }
  } catch (e) { /* 静默 */ }
  loadNotice()
  loadAll()
})
</script>

<style scoped>
.sd-page { padding: 0; }

/* 页头 */
.pg-hd { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.pg-l { display: flex; align-items: center; gap: 10px; }
.pg-r { display: flex; align-items: center; gap: 10px; }
.pg-t { font-size: 17px; font-weight: 700; color: #111827; margin: 0; }
.pg-tag {
  font-size: 11px; color: #4f46e5; background: #eef2ff;
  border: 1px solid #e0e7ff; padding: 2px 8px; border-radius: 6px;
}

/* 提示条 */
.sd-tip {
  display: flex; align-items: center; gap: 8px; font-size: 12.5px;
  padding: 9px 14px; border-radius: 10px; margin-bottom: 12px;
}
.sd-tip.lock { background: #fffbeb; border: 1px solid #fde68a; color: #92400e; }
.sd-tip.ok { background: #ecfdf5; border: 1px solid #d1fae5; color: #047857; }
.sd-tip.warn { background: #fef2f2; border: 1px solid #fecaca; color: #b91c1c; }
.sd-tip.warn .sd-link { color: #b91c1c; font-weight: 700; }
.sd-link { color: #2563eb; cursor: pointer; text-decoration: underline; margin-left: 2px; }

/* ===== 搜索区 ===== */
.sd-search { padding: 14px; margin-bottom: 16px; }
.ss-bar { display: flex; align-items: center; gap: 0; position: relative; }
.ss-ic { position: absolute; left: 14px; color: #9ca3af; pointer-events: none; }
.ss-input {
  flex: 1; height: 44px; padding: 0 40px 0 42px; font-size: 14px; font-family: inherit;
  border: 1.5px solid #e2e8f0; border-right: 0; border-radius: 10px 0 0 10px;
  color: #18181b; outline: none; background: #fff;
}
.ss-input::placeholder { color: #a1a1aa; }
.ss-input:focus { border-color: #93c5fd; box-shadow: 0 0 0 3px rgba(59,130,246,.10); }
.ss-clr {
  position: absolute; right: 108px; cursor: pointer; color: #a1a1aa;
  font-size: 18px; line-height: 1; user-select: none;
}
.ss-clr:hover { color: #52525b; }
.ss-btn {
  height: 44px; padding: 0 26px; flex: none; border: 1.5px solid #2f6bff; background: #2f6bff;
  color: #fff; font-size: 14px; font-family: inherit; cursor: pointer;
  border-radius: 0 10px 10px 0; transition: background .15s;
}
.ss-btn:hover { background: #1e4fd6; }
.ss-adv {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  margin-top: 12px; padding-top: 12px; border-top: 1px solid #f1f5f9;
}
.ss-sum { font-size: 12px; color: #6b7280; margin-left: auto; }
.w-240 { width: 240px; }

/* Element Plus 下拉：与 .ipt 的观感对齐 */
.sd-sel { font-size: 13px; }
.sd-sel :deep(.el-select__wrapper) {
  min-height: 34px; border: 1px solid #e2e8f0; border-radius: 8px;
  box-shadow: none; padding: 2px 8px; font-family: inherit;
}
.sd-sel :deep(.el-select__wrapper:hover) { border-color: #cbd5e1; }
.sd-sel :deep(.el-select__wrapper.is-focused) {
  border-color: #93c5fd; box-shadow: 0 0 0 3px rgba(59,130,246,.12);
}
.sd-sel :deep(.el-select__placeholder) { color: #9ca3af; }
.fm-c .sd-sel :deep(.el-select__wrapper) { min-height: 36px; }
.sd-sel :deep(.el-tag) { font-size: 11.5px; height: 21px; }

/* ===== 板块 ===== */
.sd-sec { margin-bottom: 22px; }
.sec-hd { display: flex; align-items: center; gap: 9px; margin-bottom: 11px; }
.sec-dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.sec-dot.demand { background: #ef4444; }
.sec-dot.supply { background: #16a34a; }
.sec-t { font-size: 14.5px; font-weight: 700; color: #111827; }
.sec-desc { font-size: 11.5px; color: #a1a1aa; }
.sec-c { font-size: 12px; color: #6b7280; }
.sec-c b { color: #111827; font-variant-numeric: tabular-nums; }
.sec-line { flex: 1; height: 1px; background: #eef2f7; }
/* ===== 翻页控件 ===== */
.sec-pager { display: flex; align-items: center; gap: 6px; flex: none; }
/* 上一页 / 下一页 */
.pg-nav {
  display: inline-flex; align-items: center; gap: 3px; height: 28px; padding: 0 10px;
  border: 1px solid #e4e4e7; border-radius: 8px; background: #fff;
  font-size: 12px; color: #374151; cursor: pointer; font-family: inherit; white-space: nowrap;
  transition: all .15s;
}
.pg-nav:hover:not(:disabled) { border-color: #93c5fd; color: #1d4ed8; background: #eff6ff; }
.pg-nav:disabled { opacity: .4; cursor: not-allowed; }
/* 页码组 */
.pg-nums { display: inline-flex; align-items: center; gap: 4px; margin: 0 2px; }
.pg-num {
  min-width: 28px; height: 28px; padding: 0 6px; border: 1px solid #e4e4e7; border-radius: 8px;
  background: #fff; font-size: 12px; color: #374151; cursor: pointer;
  font-family: inherit; font-variant-numeric: tabular-nums; transition: all .15s;
}
.pg-num:hover { border-color: #93c5fd; color: #1d4ed8; background: #eff6ff; }
.pg-num.on {
  background: #2f6bff; border-color: #2f6bff; color: #fff; font-weight: 600;
  box-shadow: 0 2px 6px -2px rgba(47, 107, 255, .5);
}
.pg-gap { color: #c3cbd6; font-size: 12px; padding: 0 1px; user-select: none; }

/* 卡片网格 */
.sd-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
@media (max-width: 1180px) { .sd-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 760px) { .sd-grid { grid-template-columns: 1fr; } }
.sd-card {
  background: #fff; border: 1px solid #e8edf3; border-radius: 12px;
  padding: 14px 15px; cursor: pointer; transition: all .16s;
  /* 纵向 flex + footer 顶到底 → 同一行卡片高度一致、底部对齐 */
  display: flex; flex-direction: column;
}
.sd-card:hover { border-color: #c7d2fe; box-shadow: 0 6px 18px -8px rgba(37,99,235,.28); transform: translateY(-1px); }
.sd-top { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 10px; }
.sd-type { flex: none; font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 5px; white-space: nowrap; }
.sd-type.demand { background: #fee2e2; color: #dc2626; }
.sd-type.supply { background: #dcfce7; color: #15803d; }
.sd-type.sm { font-size: 10.5px; padding: 1px 6px; }
.sd-title {
  font-size: 13.5px; font-weight: 600; color: #111827; line-height: 1.45;
  /* 标题最多 2 行，超出省略，避免长标题把卡片撑变形 */
  display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2;
  -webkit-box-orient: vertical; overflow: hidden;
}
.sd-meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
.sd-vn { font-size: 12.5px; font-weight: 600; color: #4f46e5; }
/* 品种 chip：淡靛底 + 靛字，比原来的整串文字清爽 */
.sd-vn-chip {
  display: inline-block; font-size: 11.5px; font-weight: 600; color: #4338ca;
  background: #eef2ff; border: 1px solid #e0e7ff;
  padding: 2px 8px; border-radius: 6px;
  max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.sd-vn-more {
  display: inline-block; font-size: 11.5px; font-weight: 600; color: #6b7280;
  background: #f4f4f5; border: 1px solid #e9e9ec;
  padding: 2px 8px; border-radius: 6px; flex: none;
}
/* 数量单独一行，视觉上更突出 */
.sd-qty-row { margin: 2px 0 6px; }
.sd-qty { font-size: 14px; font-weight: 700; color: #111827; font-variant-numeric: tabular-nums; }
.sd-chip {
  display: inline-flex; align-items: center; gap: 3px; font-size: 11px;
  color: #6b7280; background: #f4f4f5; padding: 1px 7px; border-radius: 5px;
}
.sd-row { display: flex; gap: 8px; font-size: 12px; margin-bottom: 4px; }
.sd-k { color: #9ca3af; flex: none; min-width: 52px; font-size: 11.5px; }
.sd-v { color: #374151; }
.sd-remark {
  font-size: 12px; color: #6b7280; line-height: 1.6; margin: 8px 0 0;
  display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.sd-co {
  display: flex; align-items: center; gap: 5px; margin-top: 8px;
  font-size: 11.5px; color: #92400e; background: #fffbeb;
  border: 1px solid #fde68a; padding: 4px 8px; border-radius: 6px;
}
.sd-co-ic { flex: none; }
.sd-co-n { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sd-badge { flex: none; font-size: 10px; background: #f59e0b; color: #fff; padding: 2px 7px; border-radius: 5px; white-space: nowrap; }
.sd-foot {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  margin-top: auto; padding-top: 9px; border-top: 1px solid #f4f4f5;
}
.sd-ct { display: inline-flex; align-items: center; gap: 4px; font-size: 11.5px; color: #6b7280; }
.sd-sep { color: #d4d4d8; }
.sd-time { font-size: 11px; color: #a1a1aa; flex: none; }

/* 空态 */
.sd-empty { text-align: center; padding: 44px 20px; color: #71717a; font-size: 13px; }
.sd-empty-ico { color: #c3cbd6; margin-bottom: 6px; }
.sd-empty.slim { padding: 26px 20px; background: #fafbfc; border: 1px dashed #e8edf3; border-radius: 12px; }
.sd-empty .dim { font-size: 12px; color: #a1a1aa; margin-top: 6px; }

/* 按钮 */
.btn {
  height: 34px; padding: 0 14px; border: 1px solid #e4e4e7; border-radius: 8px;
  background: #fff; font-size: 12.5px; color: #374151; cursor: pointer;
  font-family: inherit; transition: all .15s;
}
.btn:hover { border-color: #d1d5db; color: #111827; }
.btn.primary { background: #2f6bff; border-color: #2f6bff; color: #fff; display: inline-flex; align-items: center; gap: 5px; }
.btn.primary:hover { background: #1e4fd6; }
.btn.primary:disabled { opacity: .55; cursor: not-allowed; }
.btn.ghost { background: #fff; }
.btn.sm { height: 26px; padding: 0 10px; font-size: 11.5px; display: inline-flex; align-items: center; gap: 4px; }

/* 通用表单控件 */
.ipt {
  height: 34px; padding: 0 10px; border: 1px solid #e2e8f0; border-radius: 8px;
  font-size: 13px; color: #18181b; background: #fff; font-family: inherit; outline: none;
}
.ipt:focus { border-color: #93c5fd; box-shadow: 0 0 0 3px rgba(59,130,246,.12); }
.w-full { width: 100%; }

/* 发布表单 */
.sd-form { display: flex; flex-direction: column; gap: 12px; }
.fm-row { display: flex; gap: 12px; align-items: flex-start; }
.fm-l { width: 92px; flex: none; font-size: 12.5px; color: #52525b; padding-top: 9px; }
.fm-l i { color: #dc2626; font-style: normal; margin-right: 2px; }
.fm-c { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.fm-inline { flex-direction: row; }
.fm-c .ipt { height: 36px; }
.ta { height: auto; padding: 9px 10px; line-height: 1.6; resize: vertical; }
.rd { font-size: 12.5px; color: #374151; display: inline-flex; align-items: center; gap: 5px; margin-right: 16px; padding-top: 8px; }
.rd-hint { color: #a1a1aa; font-size: 11px; }
.fm-sep {
  display: flex; align-items: center; gap: 10px; font-size: 12.5px; font-weight: 600; color: #374151;
  border-top: 1px solid #f1f5f9; padding-top: 12px; margin-top: 2px;
}
.fm-sep-hint { font-size: 11px; font-weight: 400; color: #a1a1aa; }
.fm-err { font-size: 12.5px; color: #dc2626; background: #fef2f2; border: 1px solid #fecaca; padding: 8px 12px; border-radius: 8px; }
.fm-hint { font-size: 11.5px; color: #a1a1aa; }

/* 详情 */
.dt-head { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 14px; }
.dt-title { font-size: 15px; font-weight: 700; color: #111827; line-height: 1.5; }
.dt-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 20px; margin-bottom: 14px; }
.dt-i { display: flex; gap: 8px; font-size: 12.5px; color: #374151; }
.dt-block { margin-bottom: 14px; }
.dt-remark { font-size: 12.5px; color: #4b5563; line-height: 1.75; white-space: pre-wrap; margin-top: 6px; }
.ct-box { border: 1px solid #e8edf3; border-radius: 10px; padding: 12px 14px; background: #fafbfc; }
.ct-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 12.5px; font-weight: 600; color: #374151; margin-bottom: 10px;
}
.ct-row { display: flex; gap: 10px; font-size: 13px; color: #111827; padding: 4px 0; }
.ct-row .sd-k { min-width: 52px; }
.masked { color: #9ca3af; letter-spacing: .3px; }
.ct-note { font-size: 11.5px; color: #9ca3af; margin-top: 8px; line-height: 1.6; }

/* 我的发布 */
.sd-tb { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.sd-tb th {
  text-align: left; padding: 8px 10px; font-size: 11px; color: #71717a;
  background: #f4f4f5; border-bottom: 1px solid #e4e4e7; white-space: nowrap;
}
.sd-tb td { padding: 9px 10px; border-bottom: 1px solid #f4f4f5; white-space: nowrap; }
.sd-tb .ell { max-width: 200px; overflow: hidden; text-overflow: ellipsis; }
/* 产品列（varieties_name 是多品种拼接串，天然很长）：必须限宽 + 省略，
   否则会把表格撑破。此前只写在移动端媒体查询里，桌面端漏了。 */
.sd-tb .ell2 { max-width: 200px; overflow: hidden; text-overflow: ellipsis; }
/* table-layout:auto 下 td 的 max-width 兼容性不稳，内层用块级元素做真正的省略 */
.sd-tb .clamp { display: block; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 列多且 nowrap，桌面端也加横向滚动兜底，避免超出弹窗宽度后被裁切 */
.sd-tbwrap { overflow-x: auto; }
.sd-tb .num { font-variant-numeric: tabular-nums; }
.sd-tb .dim { color: #9ca3af; }
.sd-st { font-size: 11px; padding: 1px 7px; border-radius: 5px; }
.sd-st.online { background: #dcfce7; color: #15803d; }
.sd-st.offline, .sd-st.expired { background: #f4f4f5; color: #71717a; }
.sd-st.pending { background: #fef3c7; color: #92400e; }
.sd-st.rejected { background: #fee2e2; color: #dc2626; }
.sd-act { color: #2563eb; cursor: pointer; font-size: 12px; }
.sd-act:hover { text-decoration: underline; }
.sd-act.del { color: #dc2626; }
.repub-tip {
  background: #ecfdf5; border: 1px solid #a7f3d0; color: #065f46;
  font-size: 12.5px; line-height: 1.7; border-radius: 8px; padding: 8px 12px; margin-bottom: 12px;
}

/* 风险提示弹窗 */
.nt-body {
  font-size: 13.5px; line-height: 1.95; color: #374151;
  white-space: pre-wrap; max-height: 52vh; overflow-y: auto;
  background: #fffbeb; border: 1px solid #fde68a; border-radius: 10px; padding: 14px 16px;
}
.nt-foot {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 14px; gap: 10px; flex-wrap: wrap;
}
.nt-chk { display: inline-flex; align-items: center; gap: 6px; font-size: 12.5px; color: #6b7280; cursor: pointer; }
.nt-edit { font-size: 12.5px; color: #2563eb; cursor: pointer; }
.nt-edit:hover { text-decoration: underline; }

@media (max-width: 640px) {
  .ss-input { padding-left: 38px; }
  .ss-btn { padding: 0 16px; }
  .sd-search { padding: 12px; }
}
.pb-type { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.pb-type-opt { border: 1.5px solid #e4e4e7; border-radius: 10px; padding: 10px 14px; cursor: pointer;
  display: block; background: #fff; transition: border-color .15s, background .15s; }
.pb-type-opt.on { border-color: #2f6bff; background: #f0f5ff; }
.pb-type-opt input { display: none; }
.pb-type-t { display: block; font-size: 14px; font-weight: 700; color: #111827; }
.pb-type-d { display: block; font-size: 11.5px; color: #6b7280; margin-top: 2px; }
.pb-sec { font-size: 13px; font-weight: 700; color: #111827; margin: 18px 0 10px;
  padding-bottom: 8px; border-bottom: 1px solid #f0f0f2; display: flex; align-items: center; gap: 8px; }
.pb-sec-h { font-size: 11.5px; font-weight: 400; color: #9ca3af; }
.pb-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px 12px; }
.pb-grid2 { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px 12px; }
.pb-f { display: flex; flex-direction: column; }
.pb-full { grid-column: 1 / -1; }
.pb-l { font-size: 12px; color: #374151; margin-bottom: 5px; }
.pb-l i { color: #dc2626; font-style: normal; margin-right: 2px; }
.pb-ipt { height: 36px; border: 1px solid #d9d9e0; border-radius: 8px; padding: 0 10px;
  font-size: 13px; color: #111827; width: 100%; box-sizing: border-box; font-family: inherit; background: #fff; }
.pb-ipt:focus { outline: none; border-color: #2f6bff; }
textarea.pb-ipt { height: auto; padding: 8px 10px; resize: vertical; line-height: 1.6; }
.pb-h { font-size: 11.5px; color: #9ca3af; margin-top: 4px; line-height: 1.5; }

/* ===== 供货明细多行（2026-09-16）===== */
.it-head, .it-row {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 0.6fr) minmax(0, 0.64fr) minmax(0, 0.8fr) minmax(0, 0.78fr) minmax(0, 1.5fr) 62px;
  gap: 8px;
  align-items: center;
}
.it-head {
  font-size: 12px; color: #374151; margin: 14px 0 6px 2px;
}
.it-head i { color: #dc2626; font-style: normal; margin-right: 2px; }
.it-h-op { width: 62px; }
.it-rows { display: flex; flex-direction: column; gap: 8px; }
.it-row .pb-ipt { height: 36px; }
.it-c { min-width: 0; }
.it-c-name { min-width: 0; }
.it-c-op {
  display: flex; align-items: center; gap: 4px; justify-content: flex-start;
}
.it-add, .it-del {
  width: 28px; height: 36px; flex: none;
  border: 1px solid #d9d9e0; border-radius: 8px; background: #fff;
  font-size: 15px; line-height: 1; cursor: pointer; color: #6b7280;
  display: flex; align-items: center; justify-content: center;
  transition: all .15s;
}
.it-add { border-color: #2f6bff; color: #2f6bff; background: #f5f8ff; }
.it-add:hover { background: #2f6bff; color: #fff; }
.it-del:hover { border-color: #dc2626; color: #dc2626; background: #fef2f2; }

/* 详情页供货明细表 */
.dt-tb {
  width: 100%; border-collapse: separate; border-spacing: 0;
  border: 1px solid #e5e7eb; border-radius: 10px; overflow: hidden;
  margin-bottom: 12px; font-size: 12.5px;
}
.dt-tb th {
  background: #f8fafc; color: #6b7280; font-weight: 500;
  text-align: left; padding: 8px 10px; font-size: 12px;
  border-bottom: 1px solid #e5e7eb; white-space: nowrap;
}
.dt-tb td {
  padding: 9px 10px; color: #1f2937; border-bottom: 1px solid #f1f5f9;
}
.dt-tb tr:last-child td { border-bottom: none; }
.dt-tb-n { font-weight: 500; }
.dt-rc { max-width: 170px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.gsxt-link { font-size: 12.5px; color: #6b7280; text-decoration: none; padding: 0 4px; }
.gsxt-link:hover { color: #2563eb; }

/* ===== V3 视觉重设计覆盖层 ===== */

/* ===== V3 视觉重设计 · 页头 hero 化 ===== */
.pg-hd {
  background: linear-gradient(118deg, #1e3a8a 0%, #2f6bff 58%, #5a8dff 100%);
  border: none; border-radius: 16px; padding: 24px 28px;
  box-shadow: 0 14px 30px -16px rgba(47, 107, 255, .55);
  margin-bottom: 14px;
}
.pg-hd .pg-t { color: #fff; font-size: 21px; letter-spacing: .5px; }
.pg-hd .pg-tag { color: rgba(255,255,255,.9); background: rgba(255,255,255,.16); border-color: transparent; }

.sd-page .pg-hd .gsxt-link { color: rgba(255,255,255,.88); }
.sd-page .pg-hd .gsxt-link:hover { color: #fff; }
.sd-page .pg-hd .btn.ghost { background: rgba(255,255,255,.14); color: #fff; border-color: rgba(255,255,255,.38); }
.sd-page .pg-hd .btn.ghost:hover { background: rgba(255,255,255,.26); }
.sd-page .pg-hd .btn.primary { background: #fff; color: #2f6bff; border-color: #fff; }
.sd-page .pg-hd .btn.primary:hover { background: #eef4ff; }

/* 页头：求购消息通知开关（认证企业）—— 蓝底上的浅色胶囊，选中态用白底蓝点更清楚 */
.pg-hd .nt-notify {
  display: inline-flex; align-items: center; gap: 7px;
  background: rgba(255,255,255,.14); border: 1px solid rgba(255,255,255,.34);
  border-radius: 999px; padding: 4px 12px 4px 10px;
}
.pg-hd .nt-notify:hover { background: rgba(255,255,255,.24); }
.pg-hd .nt-notify.on { background: rgba(255,255,255,.22); border-color: rgba(255,255,255,.62); }
.pg-hd .nt-notify-t { color: #fff; font-size: 12.5px; white-space: nowrap; }
.pg-hd .nt-notify :deep(.el-switch__core) {
  background-color: rgba(255,255,255,.4); border-color: rgba(255,255,255,.55);
}
.pg-hd .nt-notify :deep(.el-switch.is-checked .el-switch__core) {
  background-color: #fff; border-color: #fff;
}
.pg-hd .nt-notify :deep(.el-switch.is-checked .el-switch__core .el-switch__action) {
  background-color: #2f6bff;
}
.pg-hd .nt-notify-lock { font-size: 12.5px; }

/* 提示条：轻量化 */
.sd-tip { border-radius: 11px; padding: 9px 14px; font-size: 12.5px; box-shadow: none; }

/* 搜索卡：更大圆角 + 柔和阴影 */
.sd-search { border-radius: 16px; border: 1px solid #eef2f7; box-shadow: 0 8px 22px -14px rgba(15,23,42,.14); }
.ss-input { font-size: 14.5px; }

/* 分区头：色条徽章 + 计数胶囊 */
.sec-dot { width: 9px; height: 20px; border-radius: 5px; }
.sec-dot.demand { background: linear-gradient(180deg, #ff9052, #ff6b35); box-shadow: 0 3px 8px -2px rgba(255,107,53,.5); }
.sec-dot.supply { background: linear-gradient(180deg, #34d399, #10b981); box-shadow: 0 3px 8px -2px rgba(16,185,129,.5); }
.sec-t { font-size: 15.5px; }
.sec-desc { margin-left: 2px; }
.sec-c { background: #f1f5fb; border-radius: 999px; padding: 2px 10px; font-size: 11.5px; color: #64748b; }
.sec-c b { color: #2f6bff; }

/* 帖子卡片：hover 上浮 + 层次 */
.sd-card { border-radius: 14px; border: 1px solid #e8edf3; }
.sd-card:hover {
  transform: translateY(-3px);
  border-color: #c7d8ff;
  box-shadow: 0 16px 32px -16px rgba(47,107,255,.35);
}
.sd-title { font-size: 14.5px; font-weight: 700; color: #101828; }
.sd-vn { color: #2f6bff; }
.sd-chip { background: #f1f5fb; color: #475569; border-radius: 999px; }
.sd-qty { color: #d97706; font-weight: 600; }


/* ===== 发布弹窗联系方式：账号信息自动带出 ===== */
.pb-bound {
  display: inline-flex; align-items: center; margin-left: 6px;
  font-size: 10.5px; font-weight: 600; color: #059669;
  background: #ecfdf5; border: 1px solid #a7f3d0;
  padding: 0 6px; border-radius: 999px; line-height: 16px; vertical-align: 1px;
}


/* ===== 企业名片 ===== */
.biz-link { cursor: pointer; border-bottom: 1px dashed #93b4ff; }
.biz-link:hover { color: #2f6bff; }
.biz-card { padding: 4px 2px; }
.biz-hd { display: flex; align-items: center; gap: 8px; padding-bottom: 12px; border-bottom: 1px solid #f1f5f9; }
.biz-medal { color: #d97706; }
.biz-co { font-size: 16px; font-weight: 700; color: #101828; flex: 1; }
.biz-badge {
  font-size: 11px; font-weight: 600; color: #059669; background: #ecfdf5;
  border: 1px solid #a7f3d0; padding: 1px 8px; border-radius: 999px;
}
.biz-reg {
  display: flex; align-items: center; gap: 8px; margin: 12px 0;
  font-size: 12.5px; color: #475569; background: #f0f7ff;
  border: 1px solid #d6e5ff; border-radius: 9px; padding: 8px 12px;
}
.biz-reg b { color: #2f6bff; }
.biz-reg em { font-style: normal; margin-left: auto; font-size: 11px; color: #94a3b8; }
.biz-grid { display: grid; grid-template-columns: 1fr; gap: 8px; }
.biz-kv { display: flex; align-items: baseline; gap: 10px; font-size: 13px; }
.biz-kv span { width: 110px; flex: none; color: #94a3b8; font-size: 12px; }
.biz-kv b { font-weight: 600; color: #1f2937; word-break: break-all; }
.biz-kv b.masked { color: #94a3b8; }
.biz-ft { margin-top: 12px; font-size: 11.5px; color: #94a3b8; }


/* ===== SD-MOBILE-640 移动端适配（≤640px）===== */
@media (max-width: 640px) {
  /* 弹窗：固定像素宽 → 跟随屏宽 */
  .sd-page :deep(.el-dialog) {
    width: calc(100vw - 18px) !important;
    max-width: calc(100vw - 18px);
    margin-top: 5vh !important;
  }
  .sd-page :deep(.el-dialog__header) { padding: 14px 16px 10px; }
  .sd-page :deep(.el-dialog__title) { font-size: 15px; }
  .sd-page :deep(.el-dialog__body) { padding: 10px 16px 14px; max-height: 62vh; overflow-y: auto; overflow-x: auto; }
  .sd-page :deep(.el-dialog__footer) { padding: 10px 16px 14px; }
  .sd-page :deep(.el-dialog__footer .btn) { height: 36px; }

  /* 页头 hero：纵向堆叠，操作按钮换行 */
  .pg-hd { flex-direction: column; align-items: stretch; gap: 10px; padding: 16px !important; border-radius: 13px; }
  .pg-hd .pg-t { font-size: 18px; }
  .pg-l { flex-wrap: wrap; }
  .pg-r { flex-wrap: wrap; gap: 8px; }
  .sd-page .pg-hd .btn { height: 34px; padding: 0 12px; font-size: 12px; }

  /* 提示条允许换行 */
  .sd-tip { flex-wrap: wrap; line-height: 1.6; }

  /* 搜索与筛选：下拉全宽，输入 16px 防 iOS 聚焦自动放大 */
  .sd-search { padding: 12px !important; border-radius: 13px; }
  .ss-input { height: 42px; font-size: 16px; }
  .ss-btn { height: 42px; padding: 0 18px; font-size: 14px; }
  .ss-clr { right: 84px; }
  .ss-adv { gap: 8px; }
  .sd-sel.w-240 { width: 100%; }
  .ss-sum { width: 100%; margin-left: 0; }

  /* 分区头：隐藏长描述，计数靠右 */
  .sec-hd { flex-wrap: wrap; gap: 7px; }
  .sec-desc { display: none; }
  .sec-c { margin-left: auto; }
  .sec-line { display: none; }

  /* 卡片：联系人一行防溢出 */
  .sd-card { padding: 13px; }
  .sd-title { font-size: 14px; }
  .sd-foot { flex-wrap: wrap; row-gap: 4px; }
  .sd-ct { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

  /* 详情弹窗：字段单列 */
  .dt-grid { grid-template-columns: 1fr; gap: 7px; }
  .dt-title { font-size: 14.5px; }
  .ct-head { flex-wrap: wrap; gap: 8px; }

  /* 发布表单：3列→2列，联系方式2列→1列，输入 16px 防 iOS 放大 */
  .pb-grid { grid-template-columns: 1fr 1fr; gap: 10px; }
  .pb-grid2 { grid-template-columns: 1fr; }
  .pb-type { gap: 8px; }
  .pb-type-opt { padding: 9px 10px; }
  .pb-type-d { font-size: 10.5px; }
  .pb-sec { flex-wrap: wrap; }
  .pb-ipt { font-size: 16px; }

  /* 我的发布表格：横向滚动查看 */
  .sd-tb { min-width: 640px; }
/* 我的发布：列多且 nowrap，容器加横向滚动兜底，避免最右侧操作列被弹窗裁掉 */
.sd-tbwrap { overflow-x: auto; }
.sd-tb .ell { max-width: 180px; }
.sd-tb .ell2 { max-width: 150px; overflow: hidden; text-overflow: ellipsis; }
/* 供货明细：手机端纵向堆叠 */
.it-head { display: none; }
.it-row {
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  padding: 10px;
  border: 1px solid #eef2f7;
  border-radius: 10px;
  background: #fafbfc;
}
.it-c-name { grid-column: 1 / -1; }
.it-c-remark { grid-column: 1 / -1; }
.it-c-op { grid-column: 1 / -1; justify-content: flex-end; }
.it-add, .it-del { width: auto; min-width: 34px; padding: 0 10px; }

}

/* ===== 管理员内容治理 ===== */
.sd-title { flex: 1; min-width: 0; }
.sd-mng { display: inline-flex; gap: 6px; flex: none; }
.sd-mng-b {
  font-size: 11.5px; padding: 2px 8px; border-radius: 6px; cursor: pointer;
  color: #b45309; background: #fffbeb; border: 1px solid #fde68a; white-space: nowrap;
  transition: background .15s;
}
.sd-mng-b:hover { background: #fef3c7; }
.sd-mng-b.del { color: #b91c1c; background: #fef2f2; border-color: #fecaca; }
.sd-mng-b.del:hover { background: #fee2e2; }
.btn.danger { color: #fff; background: #dc2626; border-color: #dc2626; }
.btn.danger:hover { background: #b91c1c; border-color: #b91c1c; }

/* 刷新按钮 */
.sd-refresh { display: inline-flex; align-items: center; gap: 4px; }
.sd-refresh .spin { animation: sdspin .9s linear infinite; }
.sd-refresh:disabled { opacity: .6; cursor: default; }
@keyframes sdspin { to { transform: rotate(360deg); } }

/* 个人发布不可见提示 */
.ct-private { font-size: 11.5px; color: #9ca3af; }
.sd-tip .sd-warn { color: #b45309; }

/* ===== 卡片视觉标签（2026-09-16 增强） ===== */
/* 类型徽章 */
.sd-kind {
  flex: none; font-size: 11px; font-weight: 600; line-height: 1.5;
  padding: 1px 7px; border-radius: 5px; white-space: nowrap;
}
.sd-kind.supply { color: #15803d; background: #dcfce7; border: 1px solid #bbf7d0; }
.sd-kind.demand { color: #dc2626; background: #fee2e2; border: 1px solid #fecaca; }
/* 急单徽章 */
.sd-urgent {
  flex: none; display: inline-flex; align-items: center; gap: 2px;
  font-size: 10.5px; font-weight: 600; color: #b45309;
  background: #fffbeb; border: 1px solid #fde68a;
  padding: 1px 6px; border-radius: 5px; white-space: nowrap;
}
/* 品种总数徽章 */
.sd-vn-total {
  display: inline-block; font-size: 11px; color: #6b7280;
  background: #f8fafc; border: 1px dashed #e2e8f0;
  padding: 2px 8px; border-radius: 6px; white-space: nowrap;
}
/* 数据胶囊组 */
.sd-figs { display: flex; flex-wrap: wrap; gap: 6px; margin: 2px 0 8px; }
.sd-fig {
  display: inline-flex; align-items: baseline; gap: 3px;
  font-size: 12px; font-weight: 600; color: #334155;
  background: #f8fafc; border: 1px solid #e2e8f0;
  padding: 2px 8px; border-radius: 7px;
}
.sd-fig i { font-style: normal; font-size: 10px; font-weight: 400; color: #94a3b8; }
.sd-fig em { font-style: normal; font-size: 10px; font-weight: 400; color: #94a3b8; }
.sd-fig.price { color: #b45309; background: #fffbeb; border-color: #fde68a; }
.sd-fig.price i, .sd-fig.price em { color: #c2833a; }
.sd-fig.qty { color: #1d4ed8; background: #eff6ff; border-color: #dbeafe; }
.sd-fig.qty i, .sd-fig.qty em { color: #6b8fd0; }
/* 元信息行 */
.sd-stats {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  margin-top: 6px; font-size: 11px; color: #9ca3af;
}
.sd-st { display: inline-flex; align-items: center; gap: 3px; }
.sd-st.warn { color: #d97706; font-weight: 600; }
.sd-st.dead { color: #dc2626; font-weight: 600; }

/* 数据胶囊：居中而非基线对齐（含图标） */
.sd-fig { align-items: center; }
/* 面议（无价格）弱化 */
.sd-fig.na { color: #94a3b8; background: #f8fafc; border-color: #e2e8f0; }
.sd-fig.na i { color: #b8c1cc; }
/* 认证胶囊 */
.sd-fig.ok { color: #15803d; background: #f0fdf4; border-color: #bbf7d0; }
/* 详情概览胶囊行 */
.dt-tags { display: flex; flex-wrap: wrap; gap: 6px; margin: 2px 0 12px; }
/* 明细表序号列 */
.dt-tb .dt-n { width: 30px; text-align: center; color: #9ca3af; font-size: 11px; }
/* 明细表空值弱化 */
.dt-tb .dt-na { color: #cbd5e1; }
/* 明细表品种名 chip */
.dt-vn-chip {
  display: inline-block; font-size: 12px; font-weight: 600; color: #4338ca;
  background: #eef2ff; border: 1px solid #e0e7ff;
  padding: 1px 8px; border-radius: 6px;
}
</style>
