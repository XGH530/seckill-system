/* ============================================================
   秒杀系统 · 后台管理控制台
   接口: /admin/**  (运维与管理接口,演示环境未接入鉴权,详见 AdminController 注释)
   ============================================================ */

/** 管理台 Token 的存储键:与演示页用户的 token 分开存,互不影响 */
const TOKEN_KEY = 'seckillAdminToken';

async function request(path, options) {
  options = options || {};
  const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) headers['Authorization'] = 'Bearer ' + token;

  const res = await fetch(path, Object.assign({}, options, { headers: headers }));
  let json;
  try { json = await res.json(); } catch (e) { json = { code: res.status, message: '网络异常或服务不可用' }; }
  if (json.code === 200 || json.code === 201) return json.data;

  // 401 = 没登录/登录过期 → 回到登录页;403 = 登录了但不是管理员
  if (json.code === 401) {
    localStorage.removeItem(TOKEN_KEY);
    Admin.showLogin(json.message || '登录已过期，请重新登录');
  }
  const err = new Error(json.message || ('请求失败(' + json.code + ')'));
  err.code = json.code;
  throw err;
}

const Admin = {

  state: { tab: 'dashboard', pages: {}, opsTimer: null, goodsEditing: null },

  init() {
    this.clock();
    setInterval(this.clock, 30000);

    document.querySelectorAll('#nav a').forEach(function (a) {
      a.addEventListener('click', function () { Admin.switchTab(a.dataset.tab); });
    });

    const pwd = document.getElementById('loginPwd');
    if (pwd) pwd.addEventListener('keydown', function (e) { if (e.key === 'Enter') Admin.login(); });

    // 有 token 就直接校验身份进入;没有就停在登录页
    if (localStorage.getItem(TOKEN_KEY)) {
      this.enterConsole();
    } else {
      this.showLogin();
    }
  },

  showLogin(message) {
    document.getElementById('appLayer').style.display = 'none';
    document.getElementById('loginLayer').style.display = 'flex';
    if (message) {
      document.getElementById('loginHint').innerHTML =
        '<span style="color:#e8382f">' + esc(message) + '</span><br>演示管理员：<b>admin / 123456</b>';
    }
  },

  async login() {
    const username = document.getElementById('loginUser').value.trim();
    const password = document.getElementById('loginPwd').value;
    const hint = document.getElementById('loginHint');
    if (!username || !password) {
      hint.innerHTML = '<span style="color:#e8382f">请输入账号和密码</span>';
      return;
    }
    const btn = document.getElementById('loginBtn');
    btn.disabled = true;
    btn.innerHTML = '登录中...';
    try {
      const data = await request('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username: username, password: password })
      });
      localStorage.setItem(TOKEN_KEY, data.token);
      await this.enterConsole();
    } catch (e) {
      localStorage.removeItem(TOKEN_KEY);
      hint.innerHTML = '<span style="color:#e8382f">' + esc(e.message) + '</span>';
    } finally {
      btn.disabled = false;
      btn.innerHTML = '<i class="bi bi-box-arrow-in-right"></i> 登 录';
    }
  },

  /** 校验管理员身份并进入控制台 */
  async enterConsole() {
    try {
      const me = await request('/admin/me');
      const name = me.username || 'admin';
      Admin.state.adminName = name;
      document.getElementById('loginLayer').style.display = 'none';
      document.getElementById('appLayer').style.display = 'flex';
      document.getElementById('chipProfile').textContent = '管理员 ' + name;
      this.switchTab(this.state.tab || 'dashboard');
    } catch (e) {
      localStorage.removeItem(TOKEN_KEY);
      this.showLogin(e.message);
    }
  },

  logout() {
    localStorage.removeItem(TOKEN_KEY);
    location.reload();
  },

  clock() {
    const d = new Date();
    const el = document.getElementById('clock');
    if (el) el.textContent = d.toLocaleDateString('zh-CN') + ' ' +
      d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  },

  reload() { this.switchTab(this.state.tab); },

  switchTab(tab) {
    this.state.tab = tab;
    if (this.state.opsTimer) { clearInterval(this.state.opsTimer); this.state.opsTimer = null; }

    document.querySelectorAll('#nav a').forEach(function (a) {
      a.classList.toggle('on', a.dataset.tab === tab);
    });
    const titles = {
      dashboard: '运营概览', activities: '秒杀活动', goods: '商品管理',
      orders: '订单管理', users: '用户管理', ops: '运维监控'
    };
    document.getElementById('pageTitle').textContent = titles[tab] || tab;

    const root = document.getElementById('content');
    root.innerHTML = '<div class="empty">加载中...</div>';

    const renderers = {
      dashboard: renderDashboard, activities: renderActivities, goods: renderGoods,
      orders: renderOrders, users: renderUsers, ops: renderOps
    };
    (renderers[tab] || renderDashboard)(root);
    this.refreshChips();
  },

  go(tab, page) {
    this.state.pages[tab] = Object.assign({}, this.state.pages[tab], { current: page });
    this.switchTab(tab);
  },

  /** 顶栏的两个实时指标 */
  async refreshChips() {
    try {
      const d = await request('/admin/overview');
      document.getElementById('chipOrder').textContent = '订单 ' + int(d.orderCount) + ' / 待支付 ' + int(d.unpaidCount);
      const s = d.stock || {};
      const chip = document.getElementById('chipStock');
      chip.textContent = '库存 DB ' + int(s.dbStock) + ' · Redis ' + int(s.redisStock);
      chip.className = 'chip ' + (s.consistent ? 'ok' : 'bad');
      document.getElementById('chipProfile').textContent =
        (Admin.state.adminName ? '管理员 ' + Admin.state.adminName + ' · ' : 'demo · ') +
        (d.metrics && d.metrics.uptimeText ? '运行 ' + d.metrics.uptimeText : 'H2 内存库');
    } catch (e) { /* 顶栏指标失败不打断页面 */ }
  }
};

/* ==================== 工具 ==================== */

function esc(v) {
  if (v === null || v === undefined) return '';
  return String(v).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

function int(v) { return Number(v || 0).toLocaleString('zh-CN'); }

function money(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function toast(msg, type) {
  const box = document.getElementById('toastBox');
  const el = document.createElement('div');
  el.style.cssText = 'padding:11px 18px;border-radius:10px;color:#fff;font-size:13.5px;margin-bottom:9px;' +
    'box-shadow:0 10px 26px rgba(0,0,0,.18);background:' + (type === 'error' ? '#e8382f' : type === 'success' ? '#0a7d43' : '#171a20');
  el.textContent = msg;
  box.appendChild(el);
  setTimeout(function () { el.remove(); }, 2600);
}

function pager(tab, page) {
  const current = Number(page.current || 1);
  const pages = Number(page.pages || 0) || 1;
  const total = Number(page.total || 0);
  let btns = '';
  const from = Math.max(1, current - 2);
  const to = Math.min(pages, from + 4);
  for (let i = from; i <= to; i++) {
    btns += '<button class="' + (i === current ? 'on' : '') + '" onclick="Admin.go(\'' + tab + '\',' + i + ')">' + i + '</button>';
  }
  return '<div class="pager"><span class="info">共 ' + int(total) + ' 条 · 第 ' + current + '/' + pages + ' 页</span>' +
    '<button ' + (current <= 1 ? 'disabled' : '') + ' onclick="Admin.go(\'' + tab + '\',' + (current - 1) + ')">上一页</button>' +
    btns +
    '<button ' + (current >= pages ? 'disabled' : '') + ' onclick="Admin.go(\'' + tab + '\',' + (current + 1) + ')">下一页</button></div>';
}

/** 库存/一致性徽章 */
function stockBadge(consistent) {
  return consistent
    ? '<span class="badge b-ok">一致</span>'
    : '<span class="badge b-bad">需对账</span>';
}

function activityBadge(status, text) {
  const cls = status === 1 ? 'b-ok' : status === 0 ? 'b-warn' : 'b-gray';
  return '<span class="badge ' + cls + '">' + esc(text) + '</span>';
}

function orderBadge(status, text) {
  const cls = status === 1 ? 'b-ok' : status === 0 ? 'b-warn' : status === 2 ? 'b-gray' : 'b-info';
  return '<span class="badge ' + cls + '">' + esc(text) + '</span>';
}
/* ============================================================
   ① 运营概览
   ============================================================ */

async function renderDashboard(root) {
  let d;
  try { d = await request('/admin/overview'); }
  catch (e) { root.innerHTML = '<div class="empty">加载失败：' + esc(e.message) + '</div>'; return; }

  const m = d.metrics || {};
  const v = m.volume || {}, r = m.rate || {}, l = m.latency || {}, s = d.stock || {};
  const reasons = m.failureReasons || {};
  const reasonKeys = Object.keys(reasons);

  root.innerHTML =
    '<div class="notice"><i class="bi bi-shield-lock"></i>' +
      '<div>本页所有管理接口都要求 <b>ADMIN 角色</b>（未登录 401 / 非管理员 403），' +
      '由 <code>AdminAuthInterceptor</code> 统一校验 JWT 里的 role。' +
      '演示页顶部的「运维面板」沿用原有免鉴权设计，便于现场演示与压测脚本直接调用。</div>' +
    '</div>' +

    '<div class="grid g4" style="margin-bottom:15px">' +
      kpi('bi-lightning-charge', '秒杀活动', int(d.activityCount), '进行中 ' + int(d.runningCount) + ' 个', '') +
      kpi('bi-box-seam', '商品', int(d.goodsCount), '参与秒杀的商品', 'b') +
      kpi('bi-people', '参与用户', int(d.userCount), '含压测批量注册账号', 'g') +
      kpi('bi-receipt', '订单总量', int(d.orderCount), '今日新增 ' + int(d.todayOrderCount) + ' 笔', 'a') +
    '</div>' +

    '<div class="grid g4" style="margin-bottom:15px">' +
      kpi('bi-check2-circle', '已支付', int(d.paidCount), '支付成功订单', 'g') +
      kpi('bi-hourglass-split', '待支付', int(d.unpaidCount), '超时任务会回补库存', 'a') +
      kpi('bi-x-circle', '已取消', int(d.canceledCount), '含超时关单与人工关单', 'd') +
      kpi('bi-speedometer', '受理请求', int(v.totalRequests), '排队 ' + int(v.queued) + ' · 建单 ' + int(v.orderCreated), 'b') +
    '</div>' +

    '<div class="grid g2" style="margin-bottom:15px">' +
      '<div class="panel"><h3><span class="bar"></span>库存汇总与一致性</h3>' +
        metric('初始库存合计', int(s.initialStock)) +
        metric('DB 剩余库存', int(s.dbStock)) +
        metric('Redis 剩余库存', int(s.redisStock)) +
        metric('已售出（初始 - 剩余）', int(s.soldStock)) +
        metric('未预热的活动数', int(s.notWarmed)) +
        metric('库存不一致的活动数', int(s.diffActivities)) +
        '<div style="margin-top:12px">' + stockBadge(!!s.consistent) +
          ' <span class="muted" style="font-size:12.5px;margin-left:6px">' +
          (s.consistent
            ? 'DB 库存、Redis 库存与有效订单数三者一致'
            : (s.notWarmed > 0 ? '有活动还没预热到 Redis，点击下方「一键预热」即可' : '存在差异，请到活动列表查看并一键重置库存')) +
          '</span></div>' +
      '</div>' +
      '<div class="panel"><h3><span class="bar"></span>实时业务指标</h3>' +
        metric('请求总量', int(v.totalRequests)) +
        metric('进入排队', int(v.queued) + '（' + num(r.acceptRate) + '%）') +
        metric('成功建单', int(v.orderCreated) + '（' + num(r.orderRate) + '%）') +
        metric('业务失败', int(v.businessFailed)) +
        metric('系统异常', int(v.systemError) + '（' + num(r.systemErrorRate) + '%）') +
        metric('平均响应', num(l.avgMs) + ' ms') +
        metric('P95 / P99', num(l.p95Ms) + ' ms / ' + num(l.p99Ms) + ' ms') +
        '<div class="muted" style="font-size:12px;margin-top:10px">服务端时间 ' + esc(d.serverTime) + '　·　指标为单机相对值</div>' +
      '</div>' +
    '</div>' +

    '<div class="panel"><h3><span class="bar"></span>失败原因分布</h3>' +
      (reasonKeys.length === 0
        ? '<div class="muted" style="font-size:13px">暂无失败记录（压测后这里会显示库存不足 / 重复下单 / 限流等分布）</div>'
        : reasonKeys.map(function (k) {
            const total = reasonKeys.reduce(function (a, key) { return a + Number(reasons[key] || 0); }, 0);
            const pct = total ? Math.round(Number(reasons[k] || 0) * 100 / total) : 0;
            return '<div style="margin-bottom:10px">' +
              '<div style="display:flex;justify-content:space-between;font-size:13px">' +
                '<span>' + esc(k) + '</span><span><b>' + int(reasons[k]) + '</b> 次 · ' + pct + '%</span></div>' +
              '<div class="progress"><i style="width:' + pct + '%"></i></div></div>';
          }).join('')) +
    '</div>';
}

function kpi(icon, label, value, sub, color) {
  return '<div class="panel kpi">' +
    '<div class="ic ' + (color || '') + '"><i class="bi ' + icon + '"></i></div>' +
    '<div><div class="lb">' + esc(label) + '</div><div class="vl">' + value + '</div>' +
    '<div class="sb">' + esc(sub) + '</div></div></div>';
}

function metric(label, value) {
  return '<div class="metric"><span class="muted">' + esc(label) + '</span><b>' + value + '</b></div>';
}

function num(v) {
  const n = Number(v || 0);
  return Number.isInteger(n) ? String(n) : n.toFixed(2);
}
/* ============================================================
   ② 秒杀活动
   ============================================================ */

async function renderActivities(root) {
  let list;
  try { list = await request('/admin/activities'); }
  catch (e) { root.innerHTML = '<div class="empty">加载失败：' + esc(e.message) + '</div>'; return; }
  list = list || [];

  const diffCount = list.filter(function (a) { return !a.consistent; }).length;

  root.innerHTML =
    '<div class="bar-row">' +
      '<button class="btn btn-red" onclick="Admin.warmUpAll()"><i class="bi bi-fire"></i> 一键预热全部活动</button>' +
      '<button class="btn btn-gray" onclick="Admin.reload()"><i class="bi bi-arrow-clockwise"></i> 刷新</button>' +
      '<div class="sp"></div>' +
      '<span class="chip ' + (diffCount === 0 ? 'ok' : 'bad') + '">' +
        (diffCount === 0 ? '全部活动库存一致' : diffCount + ' 个活动需对账') + '</span>' +
    '</div>' +
    '<div class="tbl-wrap"><table class="adm">' +
      '<thead><tr><th>活动</th><th>商品</th><th class="num">原价</th><th class="num">秒杀价</th>' +
      '<th class="num">初始库存</th><th class="num">DB 剩余</th><th class="num">Redis 剩余</th>' +
      '<th class="num">已售</th><th class="num">有效订单</th><th class="num">已支付</th>' +
      '<th>时间窗口</th><th>状态</th><th>对账</th><th>操作</th></tr></thead>' +
      '<tbody>' + list.map(function (a) {
        return '<tr>' +
          '<td><b>#' + a.id + '</b></td>' +
          '<td><b>' + esc(a.goodsName) + '</b><div class="muted" style="font-size:12px">' + esc(a.goodsTitle) + '</div></td>' +
          '<td class="num muted">¥' + money(a.goodsPrice) + '</td>' +
          '<td class="num"><b style="color:#e8382f">¥' + money(a.seckillPrice) + '</b></td>' +
          '<td class="num">' + int(a.initialStock) + '</td>' +
          '<td class="num"><b>' + int(a.dbStock) + '</b></td>' +
          '<td class="num">' + (a.redisStock === null || a.redisStock === undefined
            ? '<span class="badge b-warn">未预热</span>' : int(a.redisStock)) + '</td>' +
          '<td class="num">' + int(a.deducted) + '</td>' +
          '<td class="num">' + int(a.validOrders) + '</td>' +
          '<td class="num">' + int(a.paidOrders) + '</td>' +
          '<td class="muted" style="font-size:12px">' + esc(a.startTime) + '<br>' + esc(a.endTime) + '</td>' +
          '<td>' + activityBadge(a.status, a.statusText) + '</td>' +
          '<td>' + stockBadge(a.consistent) + '</td>' +
          '<td>' +
            '<button class="btn btn-gray btn-xs" onclick="Admin.resetStock(' + a.id + ',' + a.initialStock + ')">重置库存</button> ' +
            '<button class="btn btn-gray btn-xs" onclick="Admin.clearCache(' + a.id + ')">清缓存</button> ' +
            '<select class="inp" style="height:28px;font-size:12.5px;width:96px" onchange="Admin.setActivityStatus(' + a.id + ', this.value)">' +
              [['0', '未开始'], ['1', '进行中'], ['2', '已结束']].map(function (s) {
                return '<option value="' + s[0] + '"' + (String(a.status) === s[0] ? ' selected' : '') + '>' + s[1] + '</option>';
              }).join('') +
            '</select>' +
          '</td></tr>';
      }).join('') + '</tbody>' +
    '</table>' + (list.length ? '' : '<div class="empty">暂无活动</div>') + '</div>' +
    '<div class="muted" style="font-size:12.5px;margin-top:12px">' +
      '「重置库存」会同时重置 DB 库存、Redis 库存与用户占位，并重新预热；' +
      '「对账」列对比的是 DB 已扣减数与有效订单数是否一致。' +
    '</div>';
}

Admin.warmUpAll = async function () {
  try {
    const d = await request('/admin/warmup/all', { method: 'POST' });
    toast('已预热 ' + ((d && d.warmedCount) || 0) + ' 个活动', 'success');
    Admin.switchTab('activities');
  } catch (e) { toast(e.message, 'error'); }
};

Admin.resetStock = async function (id, suggest) {
  const input = prompt('把活动 #' + id + ' 的库存重置为多少？（DB / Redis / 用户占位会一起重置）', suggest || 100);
  if (input === null) return;
  const stock = parseInt(input, 10);
  if (isNaN(stock) || stock <= 0) { toast('库存必须是正整数', 'error'); return; }
  try {
    await request('/admin/activity/' + id + '/reset-stock?stock=' + stock, { method: 'POST' });
    toast('库存已重置为 ' + stock, 'success');
    Admin.switchTab('activities');
  } catch (e) { toast(e.message, 'error'); }
};

Admin.clearCache = async function (id) {
  try {
    await request('/admin/activity/' + id + '/clear-cache', { method: 'POST' });
    toast('活动缓存已清除', 'success');
  } catch (e) { toast(e.message, 'error'); }
};

Admin.setActivityStatus = async function (id, status) {
  try {
    await request('/admin/activity/' + id + '/status?status=' + status, { method: 'POST' });
    toast('活动状态已更新', 'success');
  } catch (e) { toast(e.message, 'error'); }
  Admin.switchTab('activities');
};

/* ============================================================
   ③ 商品管理
   ============================================================ */

async function renderGoods(root) {
  let list;
  try { list = await request('/admin/goods'); }
  catch (e) { root.innerHTML = '<div class="empty">加载失败：' + esc(e.message) + '</div>'; return; }
  list = list || [];

  root.innerHTML =
    '<div class="bar-row">' +
      '<button class="btn btn-red" onclick="Admin.openGoodsModal(null)"><i class="bi bi-plus-lg"></i> 新增商品</button>' +
      '<button class="btn btn-gray" onclick="Admin.reload()"><i class="bi bi-arrow-clockwise"></i> 刷新</button>' +
      '<div class="sp"></div><span class="chip info">共 ' + list.length + ' 个商品</span>' +
    '</div>' +
    '<div class="tbl-wrap"><table class="adm">' +
      '<thead><tr><th>ID</th><th>商品名称</th><th>标题</th><th class="num">原价</th><th class="num">库存</th>' +
      '<th class="num">关联活动</th><th>创建时间</th><th>操作</th></tr></thead>' +
      '<tbody>' + list.map(function (g) {
        return '<tr><td><b>#' + g.id + '</b></td>' +
          '<td><b>' + esc(g.goodsName) + '</b></td>' +
          '<td class="muted">' + esc(g.goodsTitle) + '</td>' +
          '<td class="num">¥' + money(g.goodsPrice) + '</td>' +
          '<td class="num">' + int(g.goodsStock) + '</td>' +
          '<td class="num">' + int(g.activityCount) + '</td>' +
          '<td class="muted" style="font-size:12.5px">' + esc(g.createTime) + '</td>' +
          '<td><button class="btn btn-gray btn-xs" onclick=\'Admin.openGoodsModal(' + JSON.stringify(g).replace(/'/g, "&#39;") + ')\'>编辑</button> ' +
          '<button class="btn btn-gray btn-xs" onclick="Admin.deleteGoods(' + g.id + ')">删除</button></td></tr>';
      }).join('') + '</tbody>' +
    '</table>' + (list.length ? '' : '<div class="empty">暂无商品</div>') + '</div>';
}

Admin.openGoodsModal = function (goods) {
  Admin.state.goodsEditing = goods;
  document.getElementById('goodsModalTitle').textContent = goods ? ('编辑商品 #' + goods.id) : '新增商品';
  const v = function (k) { return goods && goods[k] !== null && goods[k] !== undefined ? goods[k] : ''; };
  document.getElementById('goodsForm').innerHTML =
    field('商品名称 *', 'fGoodsName', v('goodsName'), 'text', '例如 iPhone 16 Pro') +
    field('商品标题', 'fGoodsTitle', v('goodsTitle'), 'text', '展示在活动卡片上的副标题') +
    field('图片地址', 'fGoodsImg', v('goodsImg'), 'text', '例如 /img/iphone16.jpg') +
    field('原价（元）', 'fGoodsPrice', v('goodsPrice') || '0.00', 'number', '') +
    field('商品库存', 'fGoodsStock', v('goodsStock') || 0, 'number', '') +
    field('商品详情', 'fGoodsDetail', v('goodsDetail'), 'text', '可留空');
  document.getElementById('goodsModal').style.display = 'flex';
};

Admin.closeGoodsModal = function () {
  document.getElementById('goodsModal').style.display = 'none';
  Admin.state.goodsEditing = null;
};

Admin.saveGoods = async function () {
  const body = {
    goodsName: val('fGoodsName'),
    goodsTitle: val('fGoodsTitle'),
    goodsImg: val('fGoodsImg'),
    goodsPrice: val('fGoodsPrice') || '0',
    goodsStock: val('fGoodsStock') || '0',
    goodsDetail: val('fGoodsDetail')
  };
  if (!body.goodsName) { toast('商品名称不能为空', 'error'); return; }
  const editing = Admin.state.goodsEditing;
  try {
    if (editing) {
      await request('/admin/goods/' + editing.id, { method: 'PUT', body: JSON.stringify(body) });
      toast('商品已更新', 'success');
    } else {
      await request('/admin/goods', { method: 'POST', body: JSON.stringify(body) });
      toast('商品已创建', 'success');
    }
    Admin.closeGoodsModal();
    Admin.switchTab('goods');
  } catch (e) { toast(e.message, 'error'); }
};

Admin.deleteGoods = async function (id) {
  if (!confirm('确定删除商品 #' + id + '？如果它还有进行中的秒杀活动，需要先结束活动。')) return;
  try {
    await request('/admin/goods/' + id, { method: 'DELETE' });
    toast('商品已删除', 'success');
    Admin.switchTab('goods');
  } catch (e) { toast(e.message, 'error'); }
};

function field(label, id, value, type, placeholder) {
  return '<div style="margin-bottom:13px">' +
    '<div style="font-size:12.5px;color:#6b7280;margin-bottom:5px">' + esc(label) + '</div>' +
    '<input class="inp" style="width:100%" id="' + id + '" type="' + type + '" value="' + esc(value) +
    '" placeholder="' + esc(placeholder) + '"></div>';
}

function val(id) {
  const el = document.getElementById(id);
  return el ? el.value.trim() : '';
}
/* ============================================================
   ④ 订单管理
   ============================================================ */

async function renderOrders(root) {
  const st = Admin.state.pages.orders || (Admin.state.pages.orders = { current: 1, status: '', activityId: '' });
  let data, activities = [];
  try {
    const qs = new URLSearchParams({ current: st.current, size: 10 });
    if (st.status !== '') qs.set('status', st.status);
    if (st.activityId) qs.set('activityId', st.activityId);
    data = await request('/admin/orders?' + qs.toString());
    activities = await request('/admin/activities');
  } catch (e) { root.innerHTML = '<div class="empty">加载失败：' + esc(e.message) + '</div>'; return; }

  root.innerHTML =
    '<div class="bar-row">' +
      '<select class="inp" id="oStatus">' +
        [['', '全部状态'], ['0', '待支付'], ['1', '已支付'], ['2', '已取消'], ['3', '已退款']].map(function (s) {
          return '<option value="' + s[0] + '"' + (String(st.status) === s[0] ? ' selected' : '') + '>' + s[1] + '</option>';
        }).join('') + '</select>' +
      '<select class="inp" id="oActivity">' +
        '<option value="">全部活动</option>' +
        (activities || []).map(function (a) {
          return '<option value="' + a.id + '"' + (String(st.activityId) === String(a.id) ? ' selected' : '') + '>#' +
            a.id + ' ' + esc(a.goodsName) + '</option>';
        }).join('') + '</select>' +
      '<button class="btn btn-red" onclick="Admin.searchOrders()"><i class="bi bi-search"></i> 查询</button>' +
      '<button class="btn btn-gray" onclick="Admin.resetOrders()"><i class="bi bi-arrow-counterclockwise"></i> 重置</button>' +
      '<div class="sp"></div><span class="chip info">共 ' + int(data.total) + ' 笔订单</span>' +
    '</div>' +
    '<div class="tbl-wrap"><table class="adm">' +
      '<thead><tr><th>订单号</th><th>用户</th><th>活动 / 商品</th><th class="num">秒杀价</th><th>状态</th>' +
      '<th>创建时间</th><th>支付时间</th><th>支付截止</th><th>操作</th></tr></thead>' +
      '<tbody>' + (data.records || []).map(function (o) {
        return '<tr><td class="mono" style="font-size:12px">' + esc(o.orderNo) + '</td>' +
          '<td>' + esc(o.nickname || o.username) + '<div class="muted" style="font-size:12px">' + esc(o.username) + '</div></td>' +
          '<td>#' + o.activityId + ' <b>' + esc(o.goodsName) + '</b></td>' +
          '<td class="num">¥' + money(o.seckillPrice) + '</td>' +
          '<td>' + orderBadge(o.status, o.statusText) + '</td>' +
          '<td class="muted" style="font-size:12.5px">' + esc(o.createTime) + '</td>' +
          '<td class="muted" style="font-size:12.5px">' + esc(o.payTime || '—') + '</td>' +
          '<td class="muted" style="font-size:12.5px">' + esc(o.expireTime) + '</td>' +
          '<td>' + (o.status === 0
            ? '<button class="btn btn-gray btn-xs" onclick="Admin.cancelOrder(\'' + esc(o.orderNo) + '\')">人工关单</button>'
            : '<span class="muted">—</span>') + '</td></tr>';
      }).join('') + '</tbody>' +
    '</table>' + ((data.records && data.records.length) ? pager('orders', data) : '<div class="empty">暂无订单</div>') + '</div>' +
    '<div class="muted" style="font-size:12.5px;margin-top:12px">' +
      '人工关单会：订单置为已取消 → 回补 DB 库存 → 回补 Redis 库存 → 释放该用户的「一人一单」占位，可重复点击（幂等）。' +
    '</div>';
}

Admin.searchOrders = function () {
  const st = Admin.state.pages.orders || (Admin.state.pages.orders = {});
  st.status = document.getElementById('oStatus').value;
  st.activityId = document.getElementById('oActivity').value;
  st.current = 1;
  Admin.switchTab('orders');
};

Admin.resetOrders = function () {
  Admin.state.pages.orders = { current: 1, status: '', activityId: '' };
  Admin.switchTab('orders');
};

Admin.cancelOrder = async function (orderNo) {
  if (!confirm('确定对订单 ' + orderNo + ' 执行人工关单并回补库存？')) return;
  try {
    await request('/admin/orders/' + encodeURIComponent(orderNo) + '/cancel', { method: 'POST' });
    toast('关单成功，库存已回补', 'success');
    Admin.switchTab('orders');
  } catch (e) { toast(e.message, 'error'); }
};

/* ============================================================
   ⑤ 用户管理
   ============================================================ */

async function renderUsers(root) {
  const st = Admin.state.pages.users || (Admin.state.pages.users = { current: 1, keyword: '' });
  let data;
  try {
    const qs = new URLSearchParams({ current: st.current, size: 10 });
    if (st.keyword) qs.set('keyword', st.keyword);
    data = await request('/admin/users?' + qs.toString());
  } catch (e) { root.innerHTML = '<div class="empty">加载失败：' + esc(e.message) + '</div>'; return; }

  root.innerHTML =
    '<div class="bar-row">' +
      '<input class="inp" id="uKeyword" style="width:230px" placeholder="用户名 / 昵称 / 手机号" value="' + esc(st.keyword) + '">' +
      '<button class="btn btn-red" onclick="Admin.searchUsers()"><i class="bi bi-search"></i> 查询</button>' +
      '<button class="btn btn-gray" onclick="Admin.resetUsers()"><i class="bi bi-arrow-counterclockwise"></i> 重置</button>' +
      '<div class="sp"></div><span class="chip info">共 ' + int(data.total) + ' 个账号</span>' +
    '</div>' +
    '<div class="tbl-wrap"><table class="adm">' +
      '<thead><tr><th>ID</th><th>用户名</th><th>昵称</th><th>手机号</th><th class="num">订单数</th><th>注册时间</th></tr></thead>' +
      '<tbody>' + (data.records || []).map(function (u) {
        return '<tr><td>' + u.id + '</td><td><b>' + esc(u.username) + '</b></td><td>' + esc(u.nickname) + '</td>' +
          '<td class="mono">' + esc(u.phone || '—') + '</td><td class="num">' + int(u.orderCount) + '</td>' +
          '<td class="muted" style="font-size:12.5px">' + esc(u.createTime) + '</td></tr>';
      }).join('') + '</tbody>' +
    '</table>' + ((data.records && data.records.length) ? pager('users', data) : '<div class="empty">暂无数据</div>') + '</div>' +
    '<div class="muted" style="font-size:12.5px;margin-top:12px">' +
      '压测前可用 <code>POST /auth/register</code> 批量创建账号（幂等），这里可以核对造了多少个用户。' +
    '</div>';

  const kw = document.getElementById('uKeyword');
  if (kw) kw.addEventListener('keydown', function (e) { if (e.key === 'Enter') Admin.searchUsers(); });
}

Admin.searchUsers = function () {
  const st = Admin.state.pages.users || (Admin.state.pages.users = {});
  st.keyword = document.getElementById('uKeyword').value.trim();
  st.current = 1;
  Admin.switchTab('users');
};

Admin.resetUsers = function () {
  Admin.state.pages.users = { current: 1, keyword: '' };
  Admin.switchTab('users');
};

/* ============================================================
   ⑥ 运维监控
   ============================================================ */

async function renderOps(root) {
  let h, stats;
  try {
    h = await request('/admin/health');
    try { stats = await request('/admin/stats'); } catch (e) { stats = null; }
  } catch (e) { root.innerHTML = '<div class="empty">加载失败：' + esc(e.message) + '</div>'; return; }

  const jvm = h.jvm || {};
  const pct = jvm.maxMb ? Math.min(100, Math.round(jvm.usedMb / jvm.maxMb * 100)) : 0;
  const dbOk = h.db && h.db.ok, redisOk = h.redis && h.redis.ok;

  root.innerHTML =
    '<div class="grid g3" style="margin-bottom:15px">' +
      '<div class="panel"><h3><span class="bar"></span>数据库</h3>' +
        '<div class="metric"><span><span class="dot ' + (dbOk ? 'ok' : 'bad') + '"></span>' +
        (dbOk ? '连接正常' : '连接异常') + '</span><b>' + (h.db ? h.db.latencyMs : '-') + ' ms</b></div>' +
        '<div class="muted" style="font-size:12.5px">' +
          esc(h.db && h.db.product ? h.db.product + ' ' + (h.db.version || '') : (h.db && h.db.error) || '') +
        '</div>' +
        '<div class="muted" style="font-size:12px;margin-top:8px">demo profile 使用 H2 内存库，重启即重置</div>' +
      '</div>' +
      '<div class="panel"><h3><span class="bar"></span>Redis</h3>' +
        '<div class="metric"><span><span class="dot ' + (redisOk ? 'ok' : 'bad') + '"></span>' +
        (redisOk ? '连接正常' : '连接异常') + '</span><b>' + (h.redis ? h.redis.latencyMs : '-') + ' ms</b></div>' +
        '<div class="muted" style="font-size:12.5px">' + esc((h.redis && h.redis.error) || '库存扣减 / 一人一单 / 幂等 Token') + '</div>' +
      '</div>' +
      '<div class="panel"><h3><span class="bar"></span>应用进程</h3>' +
        '<div class="metric"><span><span class="dot ok"></span>运行中</span><b>' + esc(jvm.uptimeText) + '</b></div>' +
        '<div class="muted" style="font-size:12.5px">' + esc(h.os) + ' · JDK ' + esc(jvm.javaVersion) + ' · ' + jvm.processors + ' 核</div>' +
      '</div>' +
    '</div>' +

    '<div class="panel" style="margin-bottom:15px">' +
      '<h3><span class="bar"></span>JVM 堆内存</h3>' +
      '<div style="display:flex;justify-content:space-between;font-size:13.5px">' +
        '<span id="jvmText">已用 <b>' + jvm.usedMb + ' MB</b> / 上限 ' + jvm.maxMb + ' MB</span>' +
        '<span class="muted" id="jvmPct">提交 ' + jvm.committedMb + ' MB · 使用率 ' + pct + '%</span>' +
      '</div>' +
      '<div class="progress"><i id="jvmBar" style="width:' + pct + '%"></i></div>' +
      '<div class="muted" style="font-size:12.5px;margin-top:9px">服务端时间 ' + esc(h.serverTime) + '　·　每 10 秒自动刷新</div>' +
    '</div>' +

    '<div class="panel" style="margin-bottom:15px">' +
      '<h3><span class="bar"></span>压测与运维动作</h3>' +
      '<div style="display:flex;flex-wrap:wrap;gap:10px">' +
        '<button class="btn btn-red" onclick="Admin.warmUpAll()"><i class="bi bi-fire"></i> 批量预热全部活动</button>' +
        '<button class="btn btn-gray" onclick="Admin.resetMetrics()"><i class="bi bi-eraser"></i> 重置业务指标</button>' +
        '<button class="btn btn-gray" onclick="Admin.reload()"><i class="bi bi-arrow-clockwise"></i> 刷新</button>' +
      '</div>' +
      '<div class="muted" style="font-size:12.5px;margin-top:12px">' +
        '压测流程：重置指标 → 一键预热 → 跑压测脚本 → 回到「运营概览」看成功率与 P95/P99 → 在「订单管理」核对订单数。' +
      '</div>' +
    '</div>' +

    '<div class="panel">' +
      '<h3><span class="bar"></span>在线对账原始数据 <span class="muted" style="font-weight:400;font-size:12px">(/admin/stats)</span></h3>' +
      '<pre class="mono" style="max-height:340px;overflow:auto;background:#fafbfc;border:1px solid #eef0f3;' +
        'border-radius:10px;padding:14px;font-size:12.5px;line-height:1.6">' +
        esc(stats ? JSON.stringify(stats, null, 2) : '对账接口暂不可用') +
      '</pre>' +
    '</div>';

  Admin.state.opsTimer = setInterval(function () {
    if (Admin.state.tab !== 'ops') { clearInterval(Admin.state.opsTimer); Admin.state.opsTimer = null; return; }
    request('/admin/health').then(function (latest) {
      const j = latest.jvm || {};
      const p = j.maxMb ? Math.min(100, Math.round(j.usedMb / j.maxMb * 100)) : 0;
      const bar = document.getElementById('jvmBar');
      const txt = document.getElementById('jvmText');
      const pc = document.getElementById('jvmPct');
      if (bar) bar.style.width = p + '%';
      if (txt) txt.innerHTML = '已用 <b>' + j.usedMb + ' MB</b> / 上限 ' + j.maxMb + ' MB';
      if (pc) pc.textContent = '提交 ' + j.committedMb + ' MB · 使用率 ' + p + '%';
    }).catch(function () { });
  }, 10000);
}

Admin.resetMetrics = async function () {
  try {
    await request('/admin/metrics/reset', { method: 'POST' });
    toast('业务指标已重置', 'success');
    Admin.switchTab('ops');
  } catch (e) { toast(e.message, 'error'); }
};