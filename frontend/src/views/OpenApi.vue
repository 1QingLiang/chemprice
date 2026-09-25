<template>
  <div class="op">
    <!-- ===== 头部（深色、正式） ===== -->
    <section class="op-hero">
      <div class="op-hero-txt">
        <h1>开放 API <span class="ver">v1</span></h1>
        <p>
          将平台化工行情数据接入你自己的业务系统、脚本或 AI 助手：品种搜索、品种详情、最新报价。
          <span>注册即用，赠送 200 积分 / 7 天；品种搜索 0.05 积分，其余接口 0.1 积分。</span>
        </p>
      </div>
      <div class="op-hero-r">
        <button class="op-hero-btn" @click="openDash">
          <BarChart3 />
          使用看板
        </button>
        <button v-if="user.role === 'ADMIN'" class="op-hero-btn" @click="showAdmin = true">
          <ShieldCheck />
          额度管理
        </button>
        <div class="op-badge" :class="tier.cls">
          <i></i>{{ tier.text }}
        </div>
      </div>
    </section>

    <!-- ===== 个人额度看板 ===== -->
    <section class="op-stats">
      <template v-if="st.trial">
        <div class="stat">
          <b>{{ creditsLeft }}</b>
          <span>剩余积分</span>
          <div class="bar"><i :style="{ width: creditsPct + '%' }"></i></div>
          <small>新人赠送 {{ st.creditsTotal || 200 }} 积分</small>
        </div>
        <div class="stat">
          <b>{{ trialLeftText }}</b>
          <span>试用剩余</span>
          <small>到期日 {{ st.trialEnd || '未开始计时' }}</small>
        </div>
        <div class="stat">
          <b>{{ st.keys ? st.keys.length : 0 }} <em>/ {{ st.maxKeys || 5 }}</em></b>
          <span>已建 Key</span>
        </div>
        <div class="stat">
          <b>{{ st.trialStart ? '已计时' : '未开始' }}</b>
          <span>计时状态</span>
          <small>{{ st.trialStart ? '起点 ' + st.trialStart : '创建第一个 Key 后开始' }}</small>
        </div>
      </template>

      <template v-else>
        <div class="stat">
          <b>{{ todayCalls }}</b>
          <span>今日调用</span>
          <div class="bar"><i :style="{ width: usagePct + '%' }"></i></div>
          <small>{{ st.admin ? '管理员不限额度' : '日限额 ' + (st.dailyLimit || 0) + ' 次' }}</small>
        </div>
        <div class="stat">
          <b>{{ totalCalls }}</b>
          <span>累计调用</span>
        </div>
        <div class="stat">
          <b>{{ st.keys ? st.keys.length : 0 }} <em>/ {{ st.maxKeys || 5 }}</em></b>
          <span>已建 Key</span>
        </div>
        <div class="stat">
          <b class="txt">{{ fmtNum(st.credits) }}</b>
          <span>账户积分</span>
        </div>
      </template>
    </section>

    <!-- ===== 我的 API Key（多 Key） ===== -->
    <section class="op-card">
      <h2>
        我的 API Key
        <small>{{ st.keys ? st.keys.length : 0 }} / {{ st.maxKeys || 5 }} 个</small>
        <button class="op-btn primary h2-btn" :disabled="busy || (st.keys && st.keys.length >= (st.maxKeys || 5))"
                @click="gen">新建 Key</button>
      </h2>

      <div v-if="!st.keys || !st.keys.length" class="op-none">
        <p>
          还没有 Key。<b>创建第一个 Key 的同时会开始 7 天试用计时</b>；
          Key 明文只显示一次，请立即保存。
        </p>
        <button class="op-btn primary" :disabled="busy" @click="gen">创建 API Key</button>
      </div>

      <table v-else class="op-keylist">
        <thead>
          <tr><th>名称</th><th>Key</th><th>状态</th><th>今日</th><th>累计</th><th>创建时间</th><th>最近调用</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="k in st.keys" :key="k.id">
            <td>{{ k.name }}</td>
            <td><code class="mono">{{ k.keyPrefix }}••••••</code></td>
            <td>
              <span class="tag" :class="k.status === 1 ? 'ok' : 'off'">
                {{ k.status === 1 ? '有效' : '停用' }}
              </span>
            </td>
            <td class="num">{{ k.callsToday || 0 }}</td>
            <td class="num">{{ k.totalCalls || 0 }}</td>
            <td class="num">{{ k.createdAt || '—' }}</td>
            <td class="num">{{ k.lastCalledAt || '—' }}</td>
            <td class="ops">
              <button class="op-btn sm" @click="renameKey(k)">改名</button>
              <button class="op-btn sm" @click="toggleKey(k)">{{ k.status === 1 ? '停用' : '启用' }}</button>
              <button class="op-btn sm danger" @click="removeKey(k)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- ===== 接口文档（REDFOX 版式：左导航 + 右侧端点区块） ===== -->
    <section class="op-card op-api">
      <h2>接口文档</h2>
      <div class="api-layout">
        <!-- 左侧导航 -->
        <nav class="api-nav">
          <div class="api-nav-title">接口文档目录</div>
          <div class="api-base">
            <label>
              <span>BASE URL</span>
              <button type="button" class="copy-mini" @click="copyText(BASE, 'base')">
                {{ copied['base'] ? '✓ 已复制' : '复制' }}
              </button>
            </label>
            <!-- 用 <wbr> 让长 URL 只在 / 处换行：原先 word-break:break-all 会把 open 拦腰断成 ope|n -->
            <code><template v-for="(seg, i) in baseSegs" :key="i">{{ seg }}<wbr v-if="i < baseSegs.length - 1" /></template></code>
          </div>
          <h4 class="api-nav-group">使用说明</h4>
          <a :class="{ on: activeDoc === 'auth' }" href="#doc-auth" @click="activeDoc = 'auth'">
            <span class="nav-ic">🔐</span>API 密钥与鉴权
          </a>
          <a :class="{ on: activeDoc === 'statuscodes' }" href="#doc-statuscodes" @click="activeDoc = 'statuscodes'">
            <span class="nav-ic">📋</span>常见状态码
          </a>
          <a :class="{ on: activeDoc === 'skill' }" href="#doc-skill" @click="activeDoc = 'skill'">
            <span class="nav-ic">📦</span>Skill 包与提示词
          </a>
          <a :class="{ on: activeDoc === 'mcp' }" href="#doc-mcp" @click="activeDoc = 'mcp'">
            <span class="nav-ic">🔌</span>MCP 接入（AI 客户端）
          </a>
          <h4 class="api-nav-group">接口列表</h4>
          <a v-for="ep in docs" :key="ep.id" :href="'#doc-' + ep.id"
             :class="{ on: activeDoc === ep.id }" @click="activeDoc = ep.id">
            <span class="method sm">POST</span>{{ ep.title }}
          </a>
          <a :class="{ on: activeDoc === 'meta' }" href="#doc-meta" @click="activeDoc = 'meta'">
            <span class="method sm">POST</span>能力自检
          </a>
        </nav>

        <!-- 右侧内容（只渲染当前选中的页面） -->
        <div class="api-main">

          <!-- 密钥与鉴权 -->
          <div v-if="activeDoc === 'auth'" class="api-block">
            <h3>🔐 API 密钥获取与配置</h3>
            <p class="api-lead">
              调用 API 前，请先生成本账号专属的 API Key 并完成配置：在 HTTP 请求头中添加
              <code>X-API-Key</code> 字段。密钥用于身份校验与额度计量。
            </p>
            <h4>获取步骤</h4>
            <ol class="api-steps">
              <li><b>注册即可用</b>，无需申请审批：在上方「<b>我的 API Key</b>」点击<b>创建</b>。</li>
              <li><b>创建第一个 Key 时开始 7 天试用计时</b>；赠送 <b>200 积分</b>，用完或到期需联系客服提额。</li>
              <li><b>Key 明文只显示一次</b>，请立即复制保存（系统只存哈希）。一个账号最多 5 个 Key。</li>
              <li>请求头中添加 <code>X-API-Key</code> 字段并填入密钥即可调用。</li>
              <li>解析 JSON 响应中的 <code>code</code>、<code>message</code> 与 <code>data</code> 字段；<code>code=200</code> 时业务数据在 <code>data</code>。</li>
            </ol>
            <h4>计费方式（积分）</h4>
            <table class="doc-t">
              <thead><tr><th>接口</th><th>单价</th><th>200 积分可调用</th></tr></thead>
              <tbody>
                <tr><td><code>/commodities</code> 品种搜索</td><td>0.05 积分 / 次</td><td>约 4000 次</td></tr>
                <tr><td>其余接口（详情 / 行情）</td><td>0.1 积分 / 次</td><td>约 2000 次</td></tr>
                <tr><td><code>/meta</code> 能力自检</td><td><b>免费</b></td><td>不计费</td></tr>
              </tbody>
            </table>
            <p class="api-note">
              积分按<b>账号</b>统计（多个 Key 共用同一余额）。试用期 7 天，自创建第一个 Key 起算；
              额度用尽或到期后接口返回 <code>429</code>，<b>更多额度请联系客服</b>。
            </p>
            <h4>请求头</h4>
            <table class="doc-t">
              <thead><tr><th>名称</th><th>类型</th><th>必填</th><th>说明</th><th>示例</th></tr></thead>
              <tbody>
                <tr><td><code>X-API-Key</code></td><td>string</td><td>是</td><td>平台授权令牌，每次请求必填</td><td><code>cpk_xxxxxxxx</code></td></tr>
                <tr><td><code>Content-Type</code></td><td>string</td><td>是</td><td>请求体类型（POST JSON）</td><td><code>application/json</code></td></tr>
              </tbody>
            </table>
            <h4>错误码</h4>
            <table class="doc-t">
              <thead><tr><th>code</th><th>含义</th></tr></thead>
              <tbody>
                <tr><td><code>200</code></td><td>成功，业务数据在 <code>data</code></td></tr>
                <tr><td><code>400</code></td><td>参数缺失或格式错误（如缺品种、日期格式不对）</td></tr>
                <tr><td><code>401</code></td><td>Key 缺失 / 无效 / 已停用（含账号被禁用）</td></tr>
                <tr><td><code>429</code></td><td>额度用尽：试用积分不足 / 试用已到期（需联系客服提额），或超出该账号的日调用限额（次日自动恢复）</td></tr>
              </tbody>
            </table>
            <p class="api-note">数据覆盖范围：<b>2020-01-01 至今</b>，共 2400+ 个交易日且持续更新；节假日不开盘（调休补班日正常）。</p>
          </div>

          <!-- 常见状态码 -->
          <div v-if="activeDoc === 'statuscodes'" class="api-block">
            <h3>📋 常见状态码</h3>
            <p class="api-lead">调用 API 时，响应体中的 <code>code</code> 字段表示业务状态。请根据状态码进行相应的异常处理。</p>

            <h4>成功</h4>
            <table class="doc-t">
              <thead><tr><th>code</th><th>含义</th><th>说明</th></tr></thead>
              <tbody>
                <tr>
                  <td><code class="sc-ok">200</code></td>
                  <td>成功</td>
                  <td>业务数据在 <code>data</code> 字段中。<code>message</code> 为 <code>"success"</code></td>
                </tr>
              </tbody>
            </table>

            <h4>客户端错误</h4>
            <table class="doc-t">
              <thead><tr><th>code</th><th>含义</th><th>说明</th></tr></thead>
              <tbody>
                <tr>
                  <td><code class="sc-warn">400</code></td>
                  <td>参数错误</td>
                  <td>
                    <code>message</code> 中会说明具体原因：<br>
                    · 缺少必填参数（如未传 <code>varietiesId</code> 或 <code>name</code>）<br>
                    · 日期格式错误（应为 <code>yyyy-MM-dd</code>）<br>
                    · 未传 <code>varietiesId</code> 也未传 <code>name</code><br>
                    · <code>date</code> 格式不是 yyyy-MM-dd
                  </td>
                </tr>
                <tr>
                  <td><code class="sc-err">401</code></td>
                  <td>鉴权失败</td>
                  <td>
                    · 请求头缺少 <code>X-API-Key</code><br>
                    · Key 无效或已被停用<br>
                    · 账号已被管理员禁用
                  </td>
                </tr>
                <tr>
                  <td><code class="sc-err">429</code></td>
                  <td>额度用完</td>
                  <td>
                    · <b>试用积分用尽</b>（赠送 200 积分）<br>
                    · <b>试用已到期</b>（自创建第一个 Key 起 7 天）<br>
                    · 已授权用户超出该账号的日调用限额<br>
                    积分类问题均需<b>联系客服提额</b>；日限额次日 00:00 自动恢复
                  </td>
                </tr>
              </tbody>
            </table>

            <h4>处理建议</h4>
            <table class="doc-t">
              <thead><tr><th>场景</th><th>建议</th></tr></thead>
              <tbody>
                <tr>
                  <td>收到 <code>400</code></td>
                  <td>根据 <code>message</code> 修正参数后重试，无需延时</td>
                </tr>
                <tr>
                  <td>收到 <code>401</code></td>
                  <td>检查 Key 是否正确、是否已停用；若账号被禁用需联系管理员</td>
                </tr>
                <tr>
                  <td>收到 <code>429</code></td>
                  <td>停止请求，次日自动恢复；如需更高配额请联系管理员调整日限额</td>
                </tr>
                <tr>
                  <td>网络超时</td>
                  <td>建议设置 30 秒超时，指数退避重试（最多 3 次）</td>
                </tr>
              </tbody>
            </table>

            <h4>响应格式</h4>
            <div class="codebox">
              <div class="cb-head"><span class="cb-title">成功示例</span></div>
              <pre>{
  "code": 200,
  "message": "success",
  "data": { ... }          // 业务数据在这里
}</pre>
            </div>
            <div class="codebox">
              <div class="cb-head"><span class="cb-title">失败示例</span></div>
              <pre>{
  "code": 400,
  "message": "date 格式应为 yyyy-MM-dd",
  "data": null
}</pre>
            </div>
          </div>

          <!-- Skill 包与 AI 提示词 -->
          <div v-if="activeDoc === 'skill'" class="api-block">
            <h3>📦 Skill 包与 AI 提示词</h3>
            <p class="api-lead">
              把你的 AI 助手（Claude / ChatGPT / 本地模型等）接上本平台行情数据。两种用法，任选其一。
            </p>

            <h4>方式一：下载 Skill 包 / 接口文档</h4>
            <div class="dl-row">
              <a class="dl-card" :href="DL_ZIP" download>
                <div class="dl-t">Skill 完整包 <span>.zip</span></div>
                <div class="dl-d">
                  含 SKILL.md、完整接口文档与 Python / curl 示例，
                  解压后放进 AI 工具的 skills 目录即可使用。
                </div>
                <span class="dl-btn">下载 .zip</span>
              </a>
              <a class="dl-card" :href="DL_MD" download>
                <div class="dl-t">单文件接口文档 <span>.md</span></div>
                <div class="dl-d">
                  SKILL.md 与完整 API 文档合并版，
                  适合直接粘贴给 AI 助手当知识库。
                </div>
                <span class="dl-btn">下载 .md</span>
              </a>
            </div>

            <h4>方式二：把下面的提示词发给你的 AI</h4>
            <p class="api-lead">
              复制后把最后一行换成你要查的内容即可。
              请先把 <code>cpk_你的Key</code> 换成你自己的 Key。
            </p>
            <div class="codebox">
              <div class="cb-head">
                <span class="cb-title">AI 提示词（Prompt）</span>
                <button class="copy-btn dark" @click="copyText(skillPrompt, 'prompt')">
                  {{ copied['prompt'] ? '✓ 已复制' : '复制' }}
                </button>
              </div>
              <pre>{{ skillPrompt }}</pre>
            </div>
            <p class="api-note">
              Skill 包与文档中<b>不含任何密钥</b>；Key 由你自己的账号生成，请勿分享给他人。
              额度用尽或试用到期后接口返回 <code>429</code>，更多额度请联系客服。
            </p>
          </div>

          <!-- MCP 接入 -->
          <div v-if="activeDoc === 'mcp'" class="api-block">
            <h3>🔌 MCP 接入（AI 客户端一键连接）</h3>
            <p class="api-lead">
              支持 MCP（Model Context Protocol）的 AI 客户端——WorkBuddy、Claude Desktop 等——
              可以直接把本平台行情接入为<b>内置工具</b>，<b>无需写任何代码</b>。
              配置好之后，在对话里直接问「甲醇今天什么价」就能拿到真实报价。
            </p>

            <h4>方式一：把提示词发给你的 AI，让它帮你配好（推荐）</h4>
            <p class="api-lead">
              复制下面这段，把 <code>cpk_你的Key</code> 换成你自己的 Key，发给 AI 助手即可。
            </p>
            <div class="codebox">
              <div class="cb-head">
                <span class="cb-title">MCP 配置提示词</span>
                <button class="copy-btn dark" @click="copyText(mcpPrompt, 'mcpprompt')">
                  {{ copied['mcpprompt'] ? '✓ 已复制' : '复制' }}
                </button>
              </div>
              <pre>{{ mcpPrompt }}</pre>
            </div>

            <h4>方式二：手动写入 MCP 配置文件</h4>
            <p class="api-lead">
              把下面这段加到你 AI 客户端的 MCP 配置文件（WorkBuddy 为 <code>~/.workbuddy/mcp.json</code>）
              的 <code>mcpServers</code> 里，保存后到连接器管理页点一下「<b>信任</b>」即可启用。
            </p>
            <div class="codebox">
              <div class="cb-head">
                <span class="cb-title">mcp.json</span>
                <button class="copy-btn dark" @click="copyText(mcpConfig, 'mcpcfg')">
                  {{ copied['mcpcfg'] ? '✓ 已复制' : '复制' }}
                </button>
              </div>
              <pre>{{ mcpConfig }}</pre>
            </div>

            <h4>连接后可用的工具</h4>
            <table class="doc-t">
              <thead>
                <tr><th style="width:220px">工具名</th><th>作用</th><th style="width:110px">积分消耗</th></tr>
              </thead>
              <tbody>
                <tr>
                  <td><code>search_commodity</code></td>
                  <td>按关键词搜索化工品种，返回品种 ID、单位与最新数据日</td>
                  <td>0.05 / 次</td>
                </tr>
                <tr>
                  <td><code>commodity_detail</code></td>
                  <td>查询该品种可用的报价点、价格类型与规格维度</td>
                  <td>0.1 / 次</td>
                </tr>
                <tr>
                  <td><code>latest_prices</code></td>
                  <td>查询指定交易日的行情报价（不传日期则取最新交易日）</td>
                  <td>0.1 / 次</td>
                </tr>
                <tr>
                  <td><code>api_meta</code></td>
                  <td>连通性与鉴权自检</td>
                  <td>免费</td>
                </tr>
              </tbody>
            </table>

            <p class="api-note">
              <b>关于密钥：</b>Key 由你在本地配置里自行提供，<b>平台服务端不存储任何 Key</b>，
              所有调用都记在你自己的额度上，与他人互不影响。
              当前接入地址为 HTTP 明文，请避免在不可信的公共网络下使用；
              额度用尽时工具会返回明确的提示，不会静默失败。
            </p>
          </div>

          <!-- 端点页面：只渲染 activeDoc 匹配的那一个 -->
          <template v-for="ep in docs" :key="ep.id">
            <div v-if="activeDoc === ep.id" class="api-block">
              <div class="ep-head">
                <h3>{{ ep.title }}</h3>
                <span class="mini-tag" v-if="ep.hot">需 API Key</span>
                <button class="copy-btn" @click="copyText(BASE + ep.path, 'url-' + ep.id)">
                  {{ copied['url-' + ep.id] ? '✓ 已复制' : '复制 URL' }}
                </button>
              </div>
              <div class="urlbar">
                <span class="method">POST</span>
                <code>{{ BASE + ep.path }}</code>
              </div>

              <h4>请求参数（JSON body）</h4>
              <table class="doc-t">
                <thead><tr><th>名称</th><th>类型</th><th>必填</th><th>说明</th><th>示例</th></tr></thead>
                <tbody>
                  <tr v-for="p in ep.params" :key="p.n">
                    <td><code>{{ p.n }}</code></td>
                    <td>{{ p.t }}</td>
                    <td>{{ p.r ? '是' : '否' }}</td>
                    <td>{{ p.d }}</td>
                    <td><code>{{ p.e }}</code></td>
                  </tr>
                </tbody>
              </table>

              <h4>返回字段</h4>
              <table class="doc-t">
                <thead><tr><th>字段</th><th>说明</th></tr></thead>
                <tbody>
                  <tr v-for="f in ep.fields" :key="f.f">
                    <td><code>{{ f.f }}</code></td>
                    <td>{{ f.d }}</td>
                  </tr>
                </tbody>
              </table>

              <div class="codebox">
                <div class="cb-head">
                  <span class="cb-title">Request</span>
                  <div class="cb-tabs">
                    <button v-for="(code, lang) in ep.req" :key="lang"
                            :class="{ on: (langs[ep.id] || 'curl') === lang }"
                            @click="langs[ep.id] = lang">{{ lang }}</button>
                  </div>
                  <button class="copy-btn dark" @click="copyText(ep.req[langs[ep.id] || 'curl'], 'req-' + ep.id)">
                    {{ copied['req-' + ep.id] ? '✓' : '复制' }}
                  </button>
                </div>
                <pre>{{ ep.req[langs[ep.id] || 'curl'] }}</pre>
              </div>

              <div class="codebox">
                <div class="cb-head">
                  <span class="cb-title">Response</span>
                  <button class="copy-btn dark" @click="copyText(ep.example, 'res-' + ep.id)">
                    {{ copied['res-' + ep.id] ? '✓' : '复制' }}
                  </button>
                </div>
                <pre>{{ ep.example }}</pre>
              </div>
            </div>
          </template>

          <!-- 能力自检 -->
          <div v-if="activeDoc === 'meta'" class="api-block">
            <div class="ep-head">
              <h3>能力自检</h3>
              <span class="mini-tag">需 API Key</span>
            </div>
            <div class="urlbar">
              <span class="method">POST</span>
              <code>{{ BASE }}/meta</code>
            </div>
            <p class="api-lead">无参数。返回基址下的端点清单与本页说明，适合作为调用方的启动自检与鉴权探活。</p>
            <div class="codebox">
              <div class="cb-head"><span class="cb-title">Request</span></div>
              <pre># 无参数，返回端点清单与鉴权状态（可用于连通性探活）
curl -X POST "{{ BASE }}/meta" \
  -H "X-API-Key: 你的Key" \
  -H "Content-Type: application/json" \
  -d '{}'</pre>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ===== 明文 Key 弹窗（只显示一次） ===== -->
    <AppModal :model-value="!!rawKey" bare @update:model-value="closeRaw">
      <div class="op-modal">
        <h3>API Key 已生成</h3>
        <p class="warn">请立即复制保存 —— 系统只存储哈希，关闭后将无法再次查看。</p>
        <div class="raw"><code>{{ rawKey }}</code></div>
        <div class="op-btns center">
          <button class="op-btn" @click="copyRaw">{{ copied['raw'] ? '✓ 已复制' : '复制 Key' }}</button>
          <button class="op-btn primary" @click="closeRaw">我已保存</button>
        </div>
      </div>
    </AppModal>

    <!-- ===== 使用看板弹窗（用户端 / 管理员端） ===== -->
    <AppModal v-model="showDash" bare>
      <div class="op-modal wide">
        <div class="m-head">
          <h3>使用看板</h3>
          <button class="op-x" title="关闭" @click="showDash = false">×</button>
        </div>
        <div v-if="user.role === 'ADMIN'" class="dash-tabs">
          <button :class="{ on: dashTab === 'user' }" @click="dashTab = 'user'">我的用量</button>
          <button :class="{ on: dashTab === 'admin' }" @click="dashTab = 'admin'">全站用量</button>
        </div>

        <!-- ------ 用户端 ------ -->
        <div v-if="dashTab === 'user'" class="m-body">
          <div class="dash-cards">
            <div class="dc">
              <b>{{ fmtInt(du.todayCalls) }}</b>
              <span>今日调用</span>
              <small :class="growthCls(du)">{{ growthText(du) }}</small>
            </div>
            <div class="dc"><b>{{ fmtNum(du.todayCredits) }}</b><span>今日消耗积分</span></div>
            <div class="dc"><b>{{ fmtInt(du.totalCalls) }}</b><span>总使用次数</span></div>
            <div class="dc"><b>{{ fmtNum(du.dailyCredits) }}</b><span>累计消耗积分</span></div>
            <div class="dc"><b>{{ fmtNum(du.credits) }}</b><span>剩余积分</span></div>
            <div class="dc"><b>{{ trialLeftText }}</b><span>试用剩余</span></div>
          </div>
          <h4 class="dash-h">近 {{ du.days || 14 }} 天日增长 · 调用次数</h4>
          <div class="tchart">
            <div v-for="p in du.trend || []" :key="p.date" class="tcol"
                 :title="p.date + '：' + p.calls + ' 次 / ' + fmtNum(p.credits) + ' 积分'">
              <div class="tbar-wrap"><i :style="{ height: barH(p.calls, du.trend) }"></i></div>
              <span class="tlabel">{{ p.label }}</span>
            </div>
          </div>
          <p v-if="!hasTrend(du)" class="dash-empty">暂无调用记录。日统计自本功能上线起开始累积。</p>
        </div>

        <!-- ------ 管理员端 ------ -->
        <div v-else class="m-body">
          <div class="dash-cards">
            <div class="dc"><b>{{ da.totalUsers }}</b><span>注册用户</span><small>已授权 {{ da.enabledUsers }}</small></div>
            <div class="dc"><b>{{ da.activeKeys }} <em>/ {{ da.allKeys }}</em></b><span>有效 Key</span></div>
            <div class="dc">
              <b>{{ fmtInt(da.todayCalls) }}</b>
              <span>今日调用</span>
              <small :class="growthCls(da)">{{ growthText(da) }}</small>
            </div>
            <div class="dc"><b>{{ fmtNum(da.todayCredits) }}</b><span>今日消耗积分</span></div>
            <div class="dc"><b>{{ fmtInt(da.totalCalls) }}</b><span>总使用次数</span></div>
            <div class="dc"><b>{{ fmtNum(da.dailyCredits) }}</b><span>累计消耗积分</span></div>
          </div>
          <h4 class="dash-h">近 {{ da.days || 14 }} 天日增长 · 全站调用次数</h4>
          <div class="tchart">
            <div v-for="p in da.trend || []" :key="p.date" class="tcol"
                 :title="p.date + '：' + p.calls + ' 次 / ' + fmtNum(p.credits) + ' 积分'">
              <div class="tbar-wrap"><i :style="{ height: barH(p.calls, da.trend) }"></i></div>
              <span class="tlabel">{{ p.label }}</span>
            </div>
          </div>
          <p v-if="!hasTrend(da)" class="dash-empty">
            暂无调用记录。日统计自本功能上线起开始累积；此前的历史调用只计入「总使用次数」。
          </p>

          <h4 class="dash-h">用户明细（按今日消耗排序）</h4>
          <div class="op-tablewrap">
            <table class="op-admin">
              <thead>
                <tr>
                  <th>用户</th><th>档位</th><th>剩余积分</th><th>今日消耗</th>
                  <th>累计消耗</th><th>今日调用</th><th>总调用</th><th>Key</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in dashRows" :key="r.userId">
                  <td>{{ r.nickname || r.username }}<small> @{{ r.username }}</small></td>
                  <td>
                    <span class="tag" :class="r.enabled ? 'ok' : 'off'">
                      {{ r.enabled ? '已授权' : (r.trialExpired ? '已到期' : '试用') }}
                    </span>
                  </td>
                  <td class="num">{{ fmtNum(r.credits) }}</td>
                  <td class="num">{{ fmtNum(r.todayCredits) }}</td>
                  <td class="num">{{ fmtNum(r.totalCredits) }}</td>
                  <td class="num">{{ r.todayCalls || 0 }}</td>
                  <td class="num">{{ r.totalCalls || 0 }}</td>
                  <td class="num">{{ r.activeKeyCount }} / {{ r.keyCount }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        <div class="m-foot">数据来源：开放 API 调用日志 · 日统计自功能上线起累积</div>
      </div>
    </AppModal>

    <!-- ===== 额度管理弹窗（仅管理员） ===== -->
    <AppModal v-model="showAdmin" bare>
      <div class="op-modal wide">
        <div class="m-head">
          <h3>额度管理</h3>
          <button class="op-x" title="关闭" @click="showAdmin = false">×</button>
        </div>
        <p class="m-sub">
          注册用户默认 200 积分 / 7 天试用（开箱即用）；可直接充值积分或延长试用期。
        </p>
        <input v-model="q" class="op-search" placeholder="搜索用户名 / 昵称…" />
        <div class="op-tablewrap m-body">
          <table class="op-admin">
            <thead>
              <tr>
                <th>用户</th><th>剩余积分</th>
                <th>今日消耗</th><th>试用到期</th><th>充值</th><th>延长</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="u in filtered" :key="u.userId" :class="{ dim: !u.enabled }">
                <td>
                  {{ u.nickname || u.username }}<small> @{{ u.username }}</small>
                  <span v-if="u.userStatus === 0" class="tag off">已禁用</span>
                  <span v-if="u.role === 'ADMIN'" class="tag ok">管理员</span>
                </td>
                <td class="num">{{ fmtNum(u.credits) }}</td>
                <td class="num">{{ fmtNum(u.todayCredits) }}</td>
                <td class="num">
                  <span v-if="!u.trialEnd" class="muted">未开始</span>
                  <span v-else-if="u.trialExpired" class="expired">已到期</span>
                  <span v-else>{{ u.trialEnd }}</span>
                </td>
                <td>
                  <div class="m-act">
                    <input class="op-limit sm" type="number" min="1" placeholder="100"
                           v-model.number="u._add" />
                    <button class="op-btn sm" @click="recharge(u)">充值</button>
                  </div>
                </td>
                <td>
                  <div class="m-act">
                    <input class="op-limit sm" type="number" min="0" placeholder="30"
                           v-model.number="u._days" />
                    <button class="op-btn sm" @click="extend(u)">延长</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="m-foot">共 {{ filtered.length }} 个用户 · 积分 / 有效期修改即时生效</div>
      </div>
    </AppModal>
  </div>
</template>

<script setup>
/* 开放 API：用户自助管理 Key + 管理员授权 + REDFOX 版式接口文档（2026-09-20）。
   权限模型：sys_user.open_api_enabled（管理员开）+ ADMIN 天然可用；
   key 明文只在生成响应中出现一次，页面不做任何持久化。
   接口为 POST + JSON body（兼容 GET query）。 */
import { computed, onMounted, ref } from 'vue'
import AppModal from '../components/AppModal.vue'
import { api } from '../api'
import { useAuthStore } from '../stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { BarChart3, ShieldCheck } from 'lucide-vue-next'

const BASE = 'http://82.156.8.214/api/open/v1'

/* BASE URL 切成「以 / 结尾」的片段，模板里逐段插入 <wbr>：
   这样换行只发生在斜杠后（…/api/ 换行 /open/v1），不会把 open 拆成 ope|n */
const baseSegs = BASE.split('/').map((s, i, a) => (i < a.length - 1 ? s + '/' : s))

/* Skill 包与接口文档下载地址（nginx 静态目录 /download/，文件里不含任何密钥） */
const DL_ZIP = 'http://82.156.8.214/download/chemprice-data-skill.zip'
const DL_MD = 'http://82.156.8.214/download/chemprice-api-guide.md'

/* MCP 远程服务地址（Streamable HTTP）—— 调用方在 headers 里自带 X-API-Key，服务端不存密钥 */
const MCP_URL = 'http://82.156.8.214/mcp'

const mcpPrompt = `帮我配置 ChemPrice 化工行情 MCP：
服务名称 chemprice，类型 streamableHttp，地址 ${MCP_URL}；
在 headers 中加上 X-API-Key，值为我的平台 Key（cpk_你的Key）。
配置完成后请提醒我在连接器管理页点「信任」。`

const mcpConfig = `{
  "mcpServers": {
    "chemprice": {
      "type": "streamableHttp",
      "url": "${MCP_URL}",
      "headers": { "X-API-Key": "cpk_你的Key" }
    }
  }
}`

/* 给 AI 助手用的默认提示词（页面上可一键复制） */
const skillPrompt = `你是化工行情查询助手，请使用 ChemPrice 开放数据 API 回答我的问题。

【接口信息】
基址：http://82.156.8.214/api/open/v1
鉴权：每个请求都带请求头 X-API-Key: cpk_你的Key
方式：POST + JSON body（中文直接写，无需 URL 编码）；响应格式 {code, message, data}
计费：品种搜索 0.05 积分/次，其余接口 0.1 积分/次，/meta 免费

【端点】
1) POST /commodities        body {"q":"甲醇","limit":10}   搜品种，返回 varietiesId
2) POST /commodities/detail body {"varietiesId":146}             查可用报价点 / 业务类型 / 价格类型 / 规格
3) POST /prices/latest      body {"varietiesId":146,"date":"2026-09-10","market":"上海"}
                            查某一天的行情；不传 date = 最新交易日；单次只返回一天
4) POST /meta               body {}                              连通性与鉴权自检（免费）

【执行要求】
1. 先调 /meta 自检，确认 Key 可用
2. 严格按「搜品种 → 查详情 → 查行情」取数，不要猜测品种 ID
3. 报价必须带上报价点名与数据日期，例如「甲醇 上海市场 2026-09-10 主流价 4165 元/吨」
4. 需要多天数据时按日循环调用；不要臆造或推算数据
5. 若返回 401（Key 无效）或 429（额度用尽），如实告诉我原因，不要编造结果

我的问题：<在这里写你要查的化工品和关注点>`

const auth = useAuthStore()
const user = computed(() => auth.user || {})

const st = ref({ enabled: true, admin: false, authorized: false, trial: true, keys: [] })
const overview = ref([])
const q = ref('')
const rawKey = ref('')
const copied = ref({})
const busy = ref(false)
const showAdmin = ref(false)
const showDash = ref(false)
const dashTab = ref('user')
const du = ref({})
const da = ref({})
const langs = ref({})
const activeDoc = ref('auth')

/* 账号档位徽标：管理员 / 已授权 / 试用中 / 试用已到期 */
const tier = computed(() => {
  if (st.value.admin) return { text: '管理员', cls: 'on' }
  if (st.value.authorized) return { text: '已授权', cls: 'on' }
  if (st.value.trialExpired) return { text: '试用已到期', cls: 'off' }
  if (!st.value.trialStart) return { text: '待创建 Key', cls: 'on' }
  return { text: '试用中', cls: 'on' }
})

/* 积分显示：整数不显示小数，小数最多两位（200.00 → 200，199.95 → 199.95） */
function fmtNum(v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (!isFinite(n)) return '—'
  return String(Math.round(n * 100) / 100)
}

const creditsLeft = computed(() => fmtNum(st.value.credits))
const creditsPct = computed(() => {
  const total = Number(st.value.creditsTotal || 200)
  const left = Number(st.value.credits || 0)
  if (!total) return 0
  return Math.max(0, Math.min(100, Math.round((left / total) * 100)))
})
const trialLeftText = computed(() => {
  if (!st.value.trialStart) return '未开始'
  if (st.value.trialExpired) return '已到期'
  const d = st.value.trialDaysLeft
  return d == null ? '—' : (Number(d) <= 0 ? '今日到期' : d + ' 天')
})
const todayCalls = computed(() =>
  (st.value.keys || []).reduce((a, k) => a + (k.callsToday || 0), 0))
const totalCalls = computed(() =>
  (st.value.keys || []).reduce((a, k) => a + (k.totalCalls || 0), 0))
const usagePct = computed(() => {
  const d = st.value.dailyLimit || 1
  return Math.min(100, Math.round((todayCalls.value / d) * 100))
})

const filtered = computed(() => {
  const s = q.value.trim().toLowerCase()
  if (!s) return overview.value
  return overview.value.filter((u) =>
    (u.username || '').toLowerCase().includes(s) || (u.nickname || '').toLowerCase().includes(s))
})

/* 接口文档数据（POST + JSON body） */
const docs = [
  {
    id: 'commodities',
    title: '品种搜索',
    path: '/commodities',
    params: [
      { n: 'q', t: 'string', r: 0, d: '品种名模糊匹配，中文直接写（如「甲醇」）；省略返回品种列表', e: '"甲醇"' },
      { n: 'limit', t: 'int', r: 0, d: '返回条数，1–200，默认 50', e: '10' }
    ],
    fields: [
      { f: 'varietiesId', d: '品种 ID，后续接口的主参数' },
      { f: 'name / category / unit', d: '品种名 / 分类 / 计价单位' },
      { f: 'marketCount', d: '该品种的报价点数量' },
      { f: 'latestDate', d: '该品种最新有数据的交易日' }
    ],
    req: {
      curl: '# 搜索品种名包含「甲醇」的品种，最多返回 10 条\ncurl -X POST "http://82.156.8.214/api/open/v1/commodities" \\\n  -H "X-API-Key: 你的Key" \\\n  -H "Content-Type: application/json" \\\n  -d \'{"q": "甲醇", "limit": 10}\'',
      python: 'import os, json, urllib.request\n\n# 从环境变量读取 API Key（明文不要硬编码）\nKEY = os.environ["CHEMPRICE_API_KEY"]\n\n# 构造 POST 请求：搜索品种名包含「甲醇」，返回 10 条\nreq = urllib.request.Request(\n    "http://82.156.8.214/api/open/v1/commodities",\n    data=json.dumps({"q": "甲醇", "limit": 10}).encode(),   # JSON body\n    headers={"X-API-Key": KEY, "Content-Type": "application/json"}\n)\n\nresp = json.loads(urllib.request.urlopen(req).read())\nprint(resp["data"])   # 品种列表，每项含 varietiesId、name 等',
      js: '// 搜索品种名包含「甲醇」的品种，最多返回 10 条\nconst resp = await fetch("http://82.156.8.214/api/open/v1/commodities", {\n  method: "POST",\n  headers: {\n    "X-API-Key": "你的Key",\n    "Content-Type": "application/json"\n  },\n  body: JSON.stringify({ q: "甲醇", limit: 10 })  // JSON body\n}).then(r => r.json());\n\nconsole.log(resp.data);  // 品种列表，每项含 varietiesId、name 等'
    },
    example: '{\n  "code": 200,\n  "data": [\n    { "varietiesId": 146, "name": "甲醇", "category": "化工",\n      "unit": "元/吨", "marketCount": 74627, "latestDate": "2026-09-20" }\n  ]\n}'
  },
  {
    id: 'detail',
    title: '品种详情（报价点 / 价格类型 / 规格）',
    path: '/commodities/detail',
    hot: true,
    params: [
      { n: 'varietiesId', t: 'int', r: 0, d: '品种 ID；与 name 至少传一个，ID 优先', e: '146' },
      { n: 'name', t: 'string', r: 0, d: '品种名模糊匹配（取第一个命中），中文直接写', e: '"甲醇"' },
      { n: 'limit', t: 'int', r: 0, d: '维度条数上限，1–2000，默认 500', e: '500' }
    ],
    fields: [
      { f: 'varietiesId / name / category / unit', d: '品种基本信息' },
      { f: 'businessTypes[]', d: '可用业务类型列表（如 市场价格 / 企业价格 / 国际价格）' },
      { f: 'priceTypes[]', d: '可用价格类型列表（如 厂提现汇 / 送到现汇 / 出库自提…）' },
      { f: 'specifications[]', d: '可用规格列表（如 进口 / 国产 / 优等品…）' },
      { f: 'markets[]', d: '报价点列表：每项含 market / region / businessType / priceType / specification' }
    ],
    req: {
      curl: '# 查甲醇有哪些报价点、价格类型、规格\n# 流程：先 /commodities 拿 varietiesId → 再调 /commodities/detail\ncurl -X POST "http://82.156.8.214/api/open/v1/commodities/detail" \\\n  -H "X-API-Key: 你的Key" \\\n  -H "Content-Type: application/json" \\\n  -d \'{"varietiesId": 146}\'',
      python: '# 查品种的可用维度（报价点、价格类型、规格）\n# 流程：先 /commodities 拿 varietiesId → 再调 /commodities/detail\ndetail = post("/commodities/detail", {"varietiesId": 146})\n\nprint("业务类型:", detail["businessTypes"])   # ["市场价格", ...]\nprint("价格类型:", detail["priceTypes"])       # ["厂提现汇", "送到现汇", ...]\nprint("规格:", detail["specifications"])       # ["进口", "国产", ...]\nprint("报价点数:", len(detail["markets"]))     # 报价点列表',
      js: '// 查品种的可用维度（报价点、价格类型、规格）\n// 流程：先 /commodities 拿 varietiesId → 再调 /commodities/detail\nconst detail = await post("/commodities/detail", { varietiesId: 146 });\n\nconsole.log("业务类型:", detail.businessTypes);   // ["市场价格", ...]\nconsole.log("价格类型:", detail.priceTypes);       // ["厂提现汇", "送到现汇", ...]\nconsole.log("规格:", detail.specifications);       // ["进口", "国产", ...]\nconsole.log("报价点:", detail.markets);             // [{market, region, businessType, ...}]'
    },
    example: '{\n  "code": 200,\n  "data": {\n    "varietiesId": 146, "name": "甲醇", "category": "化工", "unit": "元/吨",\n    "businessTypes": ["市场价格"],\n    "priceTypes": ["厂提现汇", "送到现汇", "出库自提", "出厂自提", "承兑自提", "库提现汇", "市场价"],\n    "specifications": ["进口", "国产"],\n    "markets": [\n      { "market": "上海", "region": "华东地区", "businessType": "市场价格",\n        "priceType": "送到现汇", "specification": "进口" },\n      { "market": "山东", "region": "华东地区", "businessType": "市场价格",\n        "priceType": "厂提现汇", "specification": "国产" }\n    ]\n  }\n}'
  },
  {
    id: 'prices',
    title: '行情查询（最新 / 单日 + 过滤）',
    path: '/prices/latest',
    hot: true,
    params: [
      { n: 'varietiesId', t: 'int', r: 0, d: '品种 ID；与 name 至少传一个，ID 优先', e: '146' },
      { n: 'name', t: 'string', r: 0, d: '品种名模糊匹配（取第一个命中），中文直接写', e: '"甲醇"' },
      { n: 'date', t: 'string', r: 0, d: '指定交易日 yyyy-MM-dd；不传 = 最新交易日', e: '"2026-09-10"' },
      { n: 'businessType', t: 'string', r: 0, d: '业务类型过滤（市场价格 / 企业价格 / 国际价格），不传=全部', e: '"市场价格"' },
      { n: 'priceType', t: 'string', r: 0, d: '价格类型过滤（厂提现汇 / 送到现汇 / …），不传=全部', e: '"厂提现汇"' },
      { n: 'market', t: 'string', r: 0, d: '报价点过滤（精确匹配，如「上海」），不传=全部', e: '"上海"' },
      { n: 'limit', t: 'int', r: 0, d: '返回条数，1–500，默认 50（真实总数看 total）', e: '500' }
    ],
    fields: [
      { f: 'date', d: '本次返回的交易日（不传 date 时为最新交易日）' },
      { f: 'businessType / priceType / market', d: '回显本次查询的过滤条件（仅传了才返回）' },
      { f: 'total', d: '匹配条件的总记录数（未受 limit 截断）' },
      { f: 'count', d: '本次实际返回的报价条数（≤ limit）' },
      { f: 'quotes[].market / region', d: '报价点名称 / 所属大区' },
      { f: 'quotes[].businessType', d: '业务类型（市场价格 / 企业价格 / 国际价格）' },
      { f: 'quotes[].specification / priceType / unit', d: '规格 / 价格类型 / 计价单位' },
      { f: 'quotes[].low / high / mid', d: '最低价 / 最高价 / 主流中间价 (low+high)/2' },
      { f: 'quotes[].changeAmt', d: '与上一交易日相比的中间价涨跌额' }
    ],
    req: {
      curl: '# ── ① 最新交易日（不传 date）──\ncurl -X POST "http://82.156.8.214/api/open/v1/prices/latest" \\\n  -H "X-API-Key: 你的Key" \\\n  -H "Content-Type: application/json" \\\n  -d \'{"varietiesId": 146, "limit": 20}\'\n\n# ── ② 指定交易日 + 报价点 + 价格类型过滤 ──\ncurl -X POST "http://82.156.8.214/api/open/v1/prices/latest" \\\n  -H "X-API-Key: 你的Key" \\\n  -H "Content-Type: application/json" \\\n  -d \'{"name": "甲醇", "date": "2026-09-10", "market": "上海", "priceType": "送到现汇"}\'\n\n# ── ③ 按业务类型过滤（市场价格 / 企业价格 / 国际价格）──\ncurl -X POST "http://82.156.8.214/api/open/v1/prices/latest" \\\n  -H "X-API-Key: 你的Key" \\\n  -H "Content-Type: application/json" \\\n  -d \'{"varietiesId": 146, "date": "2026-09-10", "businessType": "市场价格", "limit": 500}\'\n\n# ── ④ 取多天数据：按日循环（接口只支持单日）──\nfor d in 2026-09-08 2026-09-09 2026-09-10; do\n  curl -s -X POST "http://82.156.8.214/api/open/v1/prices/latest" \\\n    -H "X-API-Key: 你的Key" -H "Content-Type: application/json" \\\n    -d "{\\"varietiesId\\": 146, \\"date\\": \\"$d\\"}"\ndone',
      python: 'import os, json, urllib.request\n\nBASE = "http://82.156.8.214/api/open/v1"\nKEY  = os.environ["CHEMPRICE_API_KEY"]\n\ndef post(path, body):\n    """发送 POST JSON，自动加鉴权头，返回 data"""\n    req = urllib.request.Request(\n        BASE + path,\n        data=json.dumps(body).encode(),          # JSON body，中文直接写\n        headers={"X-API-Key": KEY, "Content-Type": "application/json"}\n    )\n    resp = json.loads(urllib.request.urlopen(req, timeout=30).read())\n    assert resp["code"] == 200, resp["message"]\n    return resp["data"]\n\n# ① 最新交易日（不传 date，取最新一天）\nlatest = post("/prices/latest", {"varietiesId": 146, "limit": 20})\n\n# ② 指定交易日 + 按报价点 / 价格类型过滤\noneday = post("/prices/latest", {\n    "name": "甲醇", "date": "2026-09-10",\n    "market": "上海",          # 可选：报价点，先用 /commodities/detail 查可选值\n    "priceType": "送到现汇",   # 可选：价格类型\n})\n\n# ③ 按业务类型过滤 + 提高返回条数\nbulk = post("/prices/latest", {\n    "varietiesId": 146, "date": "2026-09-10",\n    "businessType": "市场价格",   # 可选：市场价格 / 企业价格 / 国际价格\n    "limit": 500\n})\n\n# ④ 取历史多天：按日循环（接口一次只查一天）\nfor day in ["2026-09-08", "2026-09-09", "2026-09-10"]:\n    rows = post("/prices/latest", {"varietiesId": 146, "date": day})["quotes"]\n    print(day, len(rows), "个报价点")',
      js: '// 通用 POST 工具\nconst BASE = "http://82.156.8.214/api/open/v1";\nconst post = (path, body) => fetch(BASE + path, {\n  method: "POST",\n  headers: { "X-API-Key": "你的Key", "Content-Type": "application/json" },\n  body: JSON.stringify(body)\n}).then(r => r.json()).then(j => {\n  if (j.code !== 200) throw new Error(j.message);\n  return j.data;\n});\n\n// ① 最新交易日（不传 date，取最新一天）\nconst latest = await post("/prices/latest", { varietiesId: 146, limit: 20 });\n\n// ② 指定交易日 + 按报价点 / 价格类型过滤\nconst oneday = await post("/prices/latest", {\n  name: "甲醇", date: "2026-09-10",\n  market: "上海",          // 可选：报价点\n  priceType: "送到现汇",   // 可选：价格类型\n});\n\n// ③ 按业务类型过滤 + 提高返回条数\nconst bulk = await post("/prices/latest", {\n  varietiesId: 146, date: "2026-09-10",\n  businessType: "市场价格",   // 可选\n  limit: 500\n});\n\n// ④ 取历史多天：按日循环（接口一次只查一天）\nfor (const day of ["2026-09-08", "2026-09-09", "2026-09-10"]) {\n  const rows = (await post("/prices/latest", { varietiesId: 146, date: day })).quotes;\n  console.log(day, rows.length, "个报价点");\n}'
    },
    example: '{\n  "code": 200,\n  "data": {\n    "varietiesId": 146, "date": "2026-09-10", "total": 48, "count": 2,\n    "quotes": [\n      { "market": "上海", "region": "华东地区", "date": "2026-09-10",\n        "businessType": "市场价格", "unit": "元/吨",\n        "high": 4170.0, "low": 4160.0, "mid": 4165.0, "changeAmt": 85.0,\n        "priceType": "送到现汇", "specification": "进口" }\n    ]\n  }\n}'
  }
]

function copyText(t, k) {
  navigator.clipboard?.writeText(t).then(() => {
    copied.value = { ...copied.value, [k]: true }
    setTimeout(() => { copied.value = { ...copied.value, [k]: false } }, 1800)
  })
}

/* ===== 使用看板 ===== */

async function openDash() {
  dashTab.value = user.value.role === 'ADMIN' ? 'admin' : 'user'
  showDash.value = true
  busy.value = true
  try {
    const r = await api.get('/open-api/dashboard?days=14')
    if (r.code === 200) du.value = r.data
    if (user.value.role === 'ADMIN') {
      const r2 = await api.get('/admin/open-api/dashboard?days=14')
      if (r2.code === 200) da.value = r2.data
      const r3 = await api.get('/admin/open-api/overview')
      if (r3.code === 200) overview.value = r3.data || []
    }
  } catch (e) {
    ElMessage.error('看板加载失败')
  } finally {
    busy.value = false
  }
}

/* 管理员端用户明细：按今日消耗积分排序 */
const dashRows = computed(() => overview.value.slice().sort((a, b) =>
  (Number(b.todayCredits) || 0) - (Number(a.todayCredits) || 0) ||
  (b.totalCalls || 0) - (a.totalCalls || 0)))

function fmtInt(v) {
  if (v === null || v === undefined || v === '') return '0'
  const n = Number(v)
  return isFinite(n) ? String(Math.round(n)) : '0'
}

/* 柱高按当日最大值归一，有调用的至少给 4% 高度以便可见 */
function barH(v, trend) {
  const max = Math.max(1, ...(trend || []).map((p) => Number(p.calls) || 0))
  const pct = Math.round(((Number(v) || 0) / max) * 100)
  return Math.max(Number(v) > 0 ? 4 : 0, pct) + '%'
}

function hasTrend(d) {
  return (d.trend || []).some((p) => Number(p.calls) > 0)
}

/* 日增长 = 今天 vs 昨天 */
function dayGrowth(d) {
  const t = d.trend || []
  if (t.length < 2) return null
  const cur = Number(t[t.length - 1].calls) || 0
  const prev = Number(t[t.length - 2].calls) || 0
  return { cur, prev, diff: cur - prev }
}

function growthText(d) {
  const g = dayGrowth(d)
  if (!g) return '\u2014'
  if (g.cur === 0 && g.prev === 0) return '\u4e0e\u6628\u65e5\u6301\u5e73'
  if (g.prev === 0) return g.cur > 0 ? '\u6628\u65e5\u65e0\u8c03\u7528' : '\u4e0e\u6628\u65e5\u6301\u5e73'
  const pct = Math.round((g.diff / g.prev) * 100)
  return (g.diff >= 0 ? '\u2191 ' : '\u2193 ') + Math.abs(pct) + '% \u8f83\u6628\u65e5'
}

function growthCls(d) {
  const g = dayGrowth(d)
  if (!g || g.diff === 0) return 'flat'
  return g.diff > 0 ? 'up' : 'down'
}

async function load() {
  try {
    const r = await api.get('/open-api/status')
    if (r.code === 200) st.value = r.data
    if (user.value.role === 'ADMIN') {
      const r2 = await api.get('/admin/open-api/overview')
      if (r2.code === 200) overview.value = r2.data || []
    }
  } catch (e) {
    ElMessage.error('加载失败')
  }
}

/* 新建 Key（首次创建会启动 7 天试用计时） */
async function gen() {
  let name = ''
  const first = !(st.value.keys || []).length
  if (first && st.value.trial) {
    const t = st.value.trialStart ? '' : '创建后即开始 7 天试用计时，'
    try {
      await ElMessageBox.confirm(
        `${t}确定现在创建？`, '创建第一个 API Key', { type: 'warning', confirmButtonText: '创建' })
    } catch (e) { return }
  }
  busy.value = true
  try {
    const r = await api.post('/open-api/key', { name })
    if (r.code === 200) {
      rawKey.value = r.data.key
      copied.value = {}
      load()
    } else {
      ElMessage.error(r.message || '创建失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '创建失败')
  } finally {
    busy.value = false
  }
}

async function renameKey(k) {
  let v
  try {
    const { value } = await ElMessageBox.prompt('输入新的 Key 名称（便于区分用途）', '改名', {
      inputValue: k.name, inputPattern: /\S/, inputErrorMessage: '名称不能为空'
    })
    v = value
  } catch (e) { return }
  const r = await api.put(`/open-api/key/${k.id}`, { name: v })
  if (r.code === 200) { ElMessage.success('已改名'); load() }
  else ElMessage.error(r.message || '操作失败')
}

async function toggleKey(k) {
  const next = k.status === 1 ? 0 : 1
  if (next === 0) {
    try {
      await ElMessageBox.confirm(`停用「${k.name}」后，用它调用会立即返回 401。确定停用？`, '停用 Key', { type: 'warning' })
    } catch (e) { return }
  }
  const r = await api.put(`/open-api/key/${k.id}`, { status: next })
  if (r.code === 200) { ElMessage.success(next ? '已启用' : '已停用'); load() }
  else ElMessage.error(r.message || '操作失败')
}

async function removeKey(k) {
  try {
    await ElMessageBox.confirm(`删除「${k.name}」后不可恢复，用它调用的系统会开始报 401。确定删除？`, '删除 Key', { type: 'warning' })
  } catch (e) { return }
  const r = await api.delete(`/open-api/key/${k.id}`)
  if (r.code === 200) { ElMessage.success('已删除'); load() }
  else ElMessage.error(r.message || '操作失败')
}

async function toggleUser(u, ev) {
  const enabled = ev.target.checked
  const action = enabled ? '开通' : '关闭'
  if (!enabled) {
    try {
      await ElMessageBox.confirm(
        `关闭「${u.nickname || u.username}」的开放 API 权限？其 Key 将立即失效。`, '关闭授权', { type: 'warning' })
    } catch (e) { u.enabled = !enabled; return }
  }
  try {
    const r = await api.put('/admin/open-api/users/' + u.userId, { enabled, dailyLimit: u.dailyLimit })
    if (r.code === 200) { ElMessage.success(`已${action}`); load() }
    else { ElMessage.error(r.message || '操作失败'); u.enabled = !enabled }
  } catch (e) {
    ElMessage.error('操作失败'); u.enabled = !enabled
  }
}

async function setLimit(u, ev) {
  const v = parseInt(ev.target.value, 10)
  if (!v || v < 1) { ev.target.value = u.dailyLimit; return }
  try {
    const r = await api.put('/admin/open-api/users/' + u.userId, { enabled: !!u.enabled, dailyLimit: v })
    if (r.code === 200) { u.dailyLimit = v; ElMessage.success('日限额已更新') }
    else { ElMessage.error(r.message || '操作失败'); ev.target.value = u.dailyLimit }
  } catch (e) { ElMessage.error('操作失败'); ev.target.value = u.dailyLimit }
}

/* 积分充值（增量） */
async function recharge(u) {
  const n = Number(u._add)
  if (!n) { ElMessage.warning('请输入充值积分数量'); return }
  try {
    const r = await api.put('/admin/open-api/users/' + u.userId, { addCredits: n })
    if (r.code === 200) { ElMessage.success(`已为「${u.nickname || u.username}」充值 ${n} 积分`); u._add = null; load() }
    else ElMessage.error(r.message || '操作失败')
  } catch (e) { ElMessage.error('操作失败') }
}

/* 延长试用期：到期日 = 今天 + N 天 */
async function extend(u) {
  const d = Number(u._days)
  if (u._days === null || u._days === undefined || u._days === '') { ElMessage.warning('请输入延长天数'); return }
  try {
    const r = await api.put('/admin/open-api/users/' + u.userId, { extendDays: d })
    if (r.code === 200) { ElMessage.success(`试用期已调整为剩余 ${d} 天`); u._days = null; load() }
    else ElMessage.error(r.message || '操作失败')
  } catch (e) { ElMessage.error('操作失败') }
}

function copyRaw() {
  navigator.clipboard?.writeText(rawKey.value).then(() => { copied.value = { ...copied.value, raw: true } })
}
function closeRaw() {
  rawKey.value = ''
  load()
}

onMounted(load)
</script>

<style scoped>
.op { display: flex; flex-direction: column; gap: 16px; }

/* ===== 深色正式头部 ===== */
.op-hero {
  display: flex; align-items: center; justify-content: space-between; gap: 20px; flex-wrap: wrap;
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 60%, #243b5e 100%);
  border-radius: var(--rl); padding: 22px 26px; color: #e2e8f0;
}
.op-hero-txt h1 { font-size: 21px; font-weight: 600; color: #fff; letter-spacing: .3px; }
.op-hero-txt .ver {
  font-size: 11px; font-weight: 600; color: #93c5fd; background: rgba(59,130,246,.18);
  border: 1px solid rgba(59,130,246,.45); border-radius: 5px; padding: 1px 7px;
  vertical-align: 3px; margin-left: 8px;
}
.op-hero-txt p { margin-top: 8px; font-size: 13px; color: #94a3b8; line-height: 1.75; max-width: 660px; }
.op-hero-txt p span { color: #cbd5e1; }
.op-hero-r { display: flex; align-items: center; gap: 12px; }
.op-hero-btn {
  display: inline-flex; align-items: center; gap: 7px;
  height: 34px; padding: 0 16px; border-radius: 999px; cursor: pointer;
  font: inherit; font-size: 13px; font-weight: 500;
  color: #cbd5e1; background: rgba(255,255,255,.06);
  border: 1px solid rgba(148,163,184,.35); transition: all .15s;
}
.op-hero-btn:hover { color: #fff; border-color: #93c5fd; background: rgba(59,130,246,.18); }
.op-hero-btn svg { width: 14px; height: 14px; }
.op-badge {
  display: inline-flex; align-items: center; gap: 7px;
  padding: 7px 16px; border-radius: 999px; font-size: 13px; font-weight: 600; flex-shrink: 0;
}
.op-badge i { width: 8px; height: 8px; border-radius: 50%; }
.op-badge.on { color: #86efac; background: rgba(34,197,94,.12); border: 1px solid rgba(34,197,94,.4); }
.op-badge.on i { background: #22c55e; box-shadow: 0 0 6px #22c55e; }
.op-badge.off { color: #fcd34d; background: rgba(245,158,11,.1); border: 1px solid rgba(245,158,11,.4); }
.op-badge.off i { background: #f59e0b; }

/* ===== 统计卡 ===== */
.op-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.op-stats .stat {
  background: #fff; border: 1px solid var(--border); border-radius: var(--rl); padding: 14px 18px;
}
.op-stats .stat b { display: block; font-size: 24px; font-weight: 600; color: var(--ink2); line-height: 1.25; }
.op-stats .stat b.txt { font-size: 15px; font-weight: 500; padding-top: 5px; }
.op-stats .stat span { display: block; font-size: 12px; color: var(--ink4); margin-top: 3px; }
.op-stats .stat small { display: block; font-size: 11px; color: var(--ink4); margin-top: 8px; }
.op-stats .stat .bar { margin-top: 9px; height: 6px; border-radius: 999px; background: #e5e7eb; overflow: hidden; }
.op-stats .stat .bar i { display: block; height: 100%; background: var(--blue); border-radius: 999px; }
.op-stats.inner { margin-bottom: 16px; }
.op-stats.inner .stat { background: #f8fafc; }

/* ===== 卡片 ===== */
.op-card {
  background: #fff; border: 1px solid var(--border); border-radius: var(--rl); padding: 18px 20px;
}
.op-card h2 {
  font-size: 14.5px; font-weight: 600; margin-bottom: 14px;
  padding-bottom: 10px; border-bottom: 1px solid #f1f2f4;
}
.op-card h2 small { font-weight: 400; font-size: 12px; color: var(--ink4); margin-left: 8px; }

.op-none p { font-size: 13px; color: var(--ink3); margin-bottom: 12px; }
.op-pending {
  font-size: 13px; color: var(--ink3); line-height: 1.9;
  background: #f8fafc; border: 1px solid var(--border); border-radius: var(--r); padding: 16px 18px;
}
.op-pending b { color: var(--ink2); }

.op-btn {
  height: 32px; padding: 0 15px; border-radius: var(--r); cursor: pointer;
  font: inherit; font-size: 12.5px; border: 1px solid var(--border); background: #fff; color: var(--ink2);
  transition: all .12s;
}
.op-btn:hover:not(:disabled) { border-color: var(--blue); color: var(--blue); }
.op-btn.primary { background: var(--blue); border-color: var(--blue); color: #fff; }
.op-btn.primary:hover:not(:disabled) { background: #2563eb; color: #fff; }
.op-btn.danger { color: #b91c1c; border-color: #fecaca; }
.op-btn.danger:hover:not(:disabled) { border-color: #b91c1c; background: #fef2f2; color: #b91c1c; }
.op-btn:disabled { opacity: .55; cursor: not-allowed; }
.op-btns { display: flex; gap: 10px; margin-top: 14px; align-items: center; }
.op-btns.center { justify-content: center; }
.hint { font-size: 11.5px; color: var(--ink4); }

.op-key table { width: 100%; border-collapse: collapse; font-size: 13px; }
.op-key th {
  text-align: left; color: var(--ink4); font-weight: 500; width: 92px;
  padding: 8px 12px 8px 0; vertical-align: middle; white-space: nowrap;
}
.op-key td { padding: 8px 16px 8px 0; }
.mono { background: #f8fafc; border: 1px solid var(--border); border-radius: 6px; padding: 2px 9px; font-size: 12.5px; }

.tag { display: inline-block; font-size: 11px; border-radius: 5px; padding: 1px 8px; white-space: nowrap; }
.tag.ok { color: #166534; background: #f0fdf4; border: 1px solid #bbf7d0; }
.tag.off { color: #92400e; background: #fffbeb; border: 1px solid #fde68a; }

.usage { display: flex; align-items: center; gap: 10px; }
.usage .bar { width: 120px; height: 6px; border-radius: 999px; background: #e5e7eb; overflow: hidden; flex-shrink: 0; }
.usage .bar i { display: block; height: 100%; background: var(--blue); border-radius: 999px; }
.usage span { font-size: 12px; color: var(--ink3); white-space: nowrap; }

/* ===== 通用表格 ===== */
.op-tablewrap { overflow-x: auto; }
.op-admin { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.op-admin th, .op-admin td { padding: 10px 12px; text-align: left; border-bottom: 1px solid #f1f2f4; white-space: nowrap; }
.op-admin thead th { color: var(--ink4); font-weight: 500; font-size: 12px; background: #f8fafc; }
.op-admin tbody tr:hover { background: #fafbfc; }
.op-admin tbody tr:last-child td { border-bottom: 0; }
.op-admin tr.dim td { opacity: .55; }
.op-admin small { color: var(--ink4); margin-left: 4px; }
.op-admin td.num, .op-admin th.num { font-variant-numeric: tabular-nums; }
.op-limit {
  width: 84px; height: 28px; padding: 0 8px; border: 1px solid var(--border); border-radius: 6px;
  font: inherit; font-size: 12.5px;
}
.op-limit:focus { outline: none; border-color: var(--blue); }
.op-limit.sm { width: 62px; height: 26px; }
.m-act { display: flex; align-items: center; gap: 6px; }

/* 多 Key 列表 */
.op-keylist { width: 100%; border-collapse: collapse; font-size: 13px; }
.op-keylist th {
  text-align: left; color: var(--ink4); font-weight: 500; font-size: 12px;
  padding: 8px 10px; border-bottom: 1px solid #f1f2f4; white-space: nowrap;
}
.op-keylist td { padding: 10px; border-bottom: 1px solid #f6f7f9; vertical-align: middle; }
.op-keylist tbody tr:last-child td { border-bottom: 0; }
.op-keylist td.num, .op-keylist th.num { font-variant-numeric: tabular-nums; white-space: nowrap; }
.op-keylist td.ops { display: flex; gap: 6px; justify-content: flex-end; }
.op-card h2 .h2-btn { float: right; margin-top: -5px; }
.op-btn.sm { height: 26px; padding: 0 10px; font-size: 12px; }

.op-stats .stat b em { font-size: 13px; font-weight: 400; font-style: normal; color: var(--ink4); }
/* ===== 使用看板弹窗 ===== */
.dash-tabs { display: flex; gap: 8px; padding: 0 18px 4px; }
.dash-tabs button {
  height: 30px; padding: 0 14px; border-radius: 999px; cursor: pointer;
  font: inherit; font-size: 12.5px; border: 1px solid var(--border);
  background: #fff; color: var(--ink3); transition: all .12s;
}
.dash-tabs button:hover { border-color: var(--blue); color: var(--blue); }
.dash-tabs button.on { background: var(--blue); border-color: var(--blue); color: #fff; }
.dash-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.dash-cards .dc {
  background: #f8fafc; border: 1px solid var(--border);
  border-radius: var(--r); padding: 12px 14px;
}
.dash-cards .dc b { display: block; font-size: 20px; font-weight: 600; color: var(--ink2); line-height: 1.3; }
.dash-cards .dc b em { font-size: 12px; font-weight: 400; font-style: normal; color: var(--ink4); }
.dash-cards .dc span { display: block; font-size: 12px; color: var(--ink4); margin-top: 2px; }
.dash-cards .dc small { display: block; font-size: 11px; margin-top: 5px; color: var(--ink4); }
.dash-cards .dc small.up { color: #1d4ed8; }
.dash-cards .dc small.down { color: #b45309; }
.dash-h { font-size: 13px; font-weight: 600; margin: 18px 0 8px; color: var(--ink2); }
.tchart {
  display: flex; align-items: flex-end; gap: 4px;
  height: 138px; padding: 6px 2px 0; border-bottom: 1px solid var(--border);
}
.tcol {
  flex: 1; min-width: 0; height: 100%;
  display: flex; flex-direction: column; align-items: center; justify-content: flex-end;
}
.tbar-wrap { width: 100%; height: 100%; display: flex; align-items: flex-end; }
.tbar-wrap i { display: block; width: 100%; background: var(--blue); border-radius: 3px 3px 0 0; opacity: .85; }
.tcol:hover .tbar-wrap i { opacity: 1; }
.tlabel { font-size: 10px; color: var(--ink4); margin-top: 4px; white-space: nowrap; }
.dash-empty { font-size: 12px; color: var(--ink4); margin-top: 10px; line-height: 1.7; }
.dl-row { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; margin: 4px 0 6px; }
.dl-card {
  display: block; text-decoration: none; color: inherit;
  border: 1px solid var(--border); border-radius: var(--r);
  padding: 14px 16px; background: #f8fafc; transition: all .14s;
}
.dl-card:hover { border-color: var(--blue); background: #f1f6ff; }
.dl-t { font-size: 13.5px; font-weight: 600; color: var(--ink2); }
.dl-t span { font-size: 11px; font-weight: 400; color: var(--ink4); margin-left: 4px; }
.dl-d { font-size: 12px; color: var(--ink3); line-height: 1.7; margin: 6px 0 10px; }
.dl-btn {
  display: inline-block; font-size: 12px; color: var(--blue);
  border: 1px solid var(--blue); border-radius: 6px; padding: 3px 12px;
}
.dl-card:hover .dl-btn { background: var(--blue); color: #fff; }
@media (max-width: 720px) { .dl-row { grid-template-columns: 1fr; } }
.muted { color: var(--ink4); }
.expired { color: #b91c1c; }
.alive { color: #15803d; }

.switch { position: relative; display: inline-block; width: 36px; height: 20px; cursor: pointer; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch i { position: absolute; inset: 0; background: #d1d5db; border-radius: 999px; transition: .15s; }
.switch i::after {
  content: ''; position: absolute; top: 2px; left: 2px; width: 16px; height: 16px;
  background: #fff; border-radius: 50%; transition: .15s; box-shadow: 0 1px 3px rgba(0,0,0,.25);
}
.switch input:checked + i { background: var(--blue); }
.switch input:checked + i::after { transform: translateX(16px); }

.op-search {
  width: 260px; height: 32px; padding: 0 12px; margin-bottom: 12px;
  border: 1px solid var(--border); border-radius: var(--r); font: inherit; font-size: 13px;
  background: #fff; outline: none;
}
.op-search:focus { border-color: var(--blue); }

/* ===== 接口文档（REDFOX 版式） ===== */
.api-layout { display: grid; grid-template-columns: 208px 1fr; gap: 20px; align-items: start; }
.api-nav { position: sticky; top: 16px; display: flex; flex-direction: column; gap: 4px; }
.api-nav-title {
  font-size: 12px; font-weight: 600; color: var(--ink2); letter-spacing: .3px;
  padding-bottom: 8px; margin-bottom: 2px; border-bottom: 1px solid var(--border);
}
.api-nav-group {
  font-size: 11px; font-weight: 500; color: var(--ink4); letter-spacing: .4px;
  margin: 10px 0 2px; padding-left: 2px;
}
.api-nav-group:first-of-type { margin-top: 6px; }
.api-base {
  background: #f8fafc; border: 1px solid var(--border); border-radius: var(--r);
  padding: 10px 12px; margin-bottom: 10px;
}
.api-base label { display: flex; align-items: center; justify-content: space-between; gap: 6px; font-size: 10.5px; color: var(--ink4); letter-spacing: .5px; }
/* word-break:normal + <wbr>：只在斜杠处换行；原 break-all 会把 open 断成 ope|n */
.api-base code { font-size: 11px; word-break: normal; overflow-wrap: break-word; line-height: 1.7; color: var(--ink2); }
.copy-mini { border: 1px solid var(--border); background: #fff; color: var(--ink4); font-size: 10px; line-height: 1; padding: 3px 7px; border-radius: 5px; cursor: pointer; white-space: nowrap; font-family: inherit; }
.copy-mini:hover { border-color: var(--ink2); color: var(--ink2); }
.api-nav a {
  display: flex; align-items: center; gap: 7px;
  padding: 8px 10px; border-radius: var(--r); cursor: pointer; text-decoration: none;
  font-size: 12.5px; color: var(--ink2); border: 1px solid transparent;
}
.api-nav a:hover { background: #f8fafc; }
.api-nav a.on { background: #eff6ff; border-color: #bfdbfe; color: var(--blue); font-weight: 500; }
.method {
  font-size: 10.5px; font-weight: 700; color: #166534; background: #f0fdf4;
  border: 1px solid #bbf7d0; border-radius: 5px; padding: 1px 7px; flex-shrink: 0;
}
.method.sm { padding: 0 5px; font-size: 10px; }
.sc-ok { background: #dcfce7; color: #166534; border: 1px solid #bbf7d0; border-radius: 4px; padding: 1px 7px; font-weight: 600; }
.sc-warn { background: #fef9c3; color: #854d0e; border: 1px solid #fde68a; border-radius: 4px; padding: 1px 7px; font-weight: 600; }
.sc-err { background: #fee2e2; color: #991b1b; border: 1px solid #fecaca; border-radius: 4px; padding: 1px 7px; font-weight: 600; }

.api-block {
  border: 1px solid var(--border); border-radius: var(--rl);
  padding: 18px 20px; margin-bottom: 18px; scroll-margin-top: 16px;
}
.api-block > h3 { font-size: 15px; font-weight: 600; margin-bottom: 6px; }
.api-lead { font-size: 13px; color: var(--ink3); line-height: 1.85; margin: 6px 0 10px; }
.api-lead code, .api-note code, .doc-t code, .urlbar code {
  background: #f8fafc; border: 1px solid var(--border); border-radius: 5px;
  padding: 1px 7px; font-size: 12px; font-family: ui-monospace, Consolas, monospace;
}
.api-note {
  font-size: 12px; color: var(--ink4); background: #f8fafc;
  border-radius: var(--r); padding: 9px 12px; line-height: 1.8;
}
.api-block h4 { font-size: 12.5px; font-weight: 600; color: var(--ink3); margin: 16px 0 8px; }
.api-steps { margin: 8px 0 4px; padding-left: 20px; }
.api-steps li { font-size: 13px; color: var(--ink3); line-height: 2; }
.api-steps code {
  background: #fff7ed; border: 1px solid #fed7aa; border-radius: 5px;
  padding: 1px 7px; font-size: 12px; color: #9a3412;
}

.ep-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
.ep-head h3 { font-size: 15px; font-weight: 600; }
.mini-tag {
  font-size: 10.5px; color: #92400e; background: #fffbeb;
  border: 1px solid #fde68a; border-radius: 5px; padding: 1px 7px;
}
.urlbar {
  display: flex; align-items: center; gap: 10px;
  background: #f8fafc; border: 1px solid var(--border); border-radius: var(--r);
  padding: 10px 14px; overflow-x: auto;
}
.urlbar code { background: transparent; border: 0; padding: 0; font-size: 12.5px; white-space: nowrap; }

.doc-t { width: 100%; border-collapse: collapse; font-size: 12.5px; margin-bottom: 6px; }
.doc-t th, .doc-t td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f1f2f4; vertical-align: top; }
.doc-t th { color: var(--ink4); font-weight: 500; font-size: 12px; background: #f8fafc; white-space: nowrap; }
.doc-t td:first-child code { white-space: nowrap; }

/* 代码块（带语言 tab） */
.codebox {
  background: #0f172a; border-radius: var(--r); margin: 12px 0 4px; overflow: hidden;
}
.cb-head {
  display: flex; align-items: center; gap: 12px;
  padding: 8px 12px; border-bottom: 1px solid #1e293b;
}
.cb-title { font-size: 11.5px; font-weight: 600; color: #94a3b8; letter-spacing: .5px; }
.cb-tabs { display: flex; gap: 4px; flex: 1; }
.cb-tabs button {
  height: 24px; padding: 0 10px; border: 0; border-radius: 6px; cursor: pointer;
  font: inherit; font-size: 11.5px; color: #94a3b8; background: transparent;
}
.cb-tabs button:hover { color: #e2e8f0; }
.cb-tabs button.on { color: #fff; background: #334155; }
.copy-btn {
  height: 26px; padding: 0 10px; border: 1px solid var(--border); border-radius: 6px;
  background: #fff; color: var(--ink3); font: inherit; font-size: 11.5px; cursor: pointer;
  margin-left: auto; flex-shrink: 0;
}
.copy-btn:hover { border-color: var(--blue); color: var(--blue); }
.copy-btn.dark {
  background: #1e293b; border-color: #334155; color: #94a3b8; margin-left: 0;
}
.copy-btn.dark:hover { color: #fff; border-color: #64748b; }
.codebox pre, .op-docs pre {
  margin: 0; padding: 13px 16px; color: #e2e8f0;
  font-size: 12px; line-height: 1.75; overflow-x: auto;
  font-family: ui-monospace, Consolas, monospace; white-space: pre;
}
.api-note b { color: var(--ink3); }

/* 明文 Key 弹窗 */
.op-modal {
  width: 520px; max-width: 100%; background: #fff; border-radius: var(--rl); padding: 22px 24px;
  box-shadow: 0 20px 50px -12px rgba(0,0,0,.35);
}
.op-modal.wide { width: 880px; max-width: 96vw; max-height: 84vh; display: flex; flex-direction: column; }
.op-modal h3 { font-size: 16px; font-weight: 600; }
.op-modal .warn { font-size: 12.5px; color: #b45309; margin: 10px 0 14px; line-height: 1.7; }
.op-modal .raw {
  background: #0f172a; color: #7dd3fc; border-radius: var(--r); padding: 14px 16px;
  font-size: 13px; word-break: break-all; line-height: 1.7;
}
.op-modal .raw code { font-family: ui-monospace, Consolas, monospace; }
.op-modal .m-head { display: flex; align-items: center; justify-content: space-between; }
.op-modal .m-head h3 { font-size: 16px; font-weight: 600; }
.op-x {
  width: 30px; height: 30px; border: 0; background: #f1f2f4; border-radius: 8px;
  font-size: 17px; color: var(--ink3); cursor: pointer; line-height: 1;
}
.op-x:hover { background: #e4e4e7; color: var(--ink2); }
.op-modal .m-sub { font-size: 12px; color: var(--ink4); margin: 6px 0 12px; }
.op-modal .op-search { width: 100%; }
.op-modal .m-body {
  max-height: 54vh; overflow-y: auto;
  border: 1px solid var(--border); border-radius: var(--r);
}
.op-modal .m-body .op-admin thead th { position: sticky; top: 0; z-index: 1; }
.op-modal .m-foot { font-size: 11.5px; color: var(--ink4); margin-top: 10px; }

@media (max-width: 900px) {
  .op-stats { grid-template-columns: repeat(2, 1fr); }
  .api-layout { grid-template-columns: 1fr; }
  .api-nav { position: static; flex-direction: row; flex-wrap: wrap; }
  .api-base { width: 100%; }
}
@media (max-width: 760px) {
  .op-hero { flex-direction: column; align-items: flex-start; }
  .op-key th { width: 78px; }
}
</style>
