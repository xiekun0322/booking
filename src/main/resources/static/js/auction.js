/**
 * 拍卖页交互（主干流程）。
 *
 * 覆盖：列表（竞拍中/待拍/已成交/流拍）→ 详情 → 缴保证金 → 出价 → 我的成交 → 付成交款。
 * 刻意不做的：分页器、图片、出价记录分页、动画与响应式细节——先把主流程跑通。
 *
 * 约定：
 *   - 所有请求走 window.apiFetch（自带 401 登录弹窗与防重放头）；
 *   - 金额一律按字符串保留两位，不在前端做四舍五入决策；
 *   - 状态文案由后端给（DTO.statusText），前端只做样式映射（决策 D1）。
 */
(function () {
    'use strict';

    var state = { status: 'ACTIVE', items: [], detail: null, myMode: 'deposit' };

    // ==================== 工具 ====================

    function $(id) { return document.getElementById(id); }

    function toast(msg) {
        var el = $('toast');
        el.textContent = msg;
        el.classList.add('show');
        clearTimeout(el.__t);
        el.__t = setTimeout(function () { el.classList.remove('show'); }, 2600);
    }

    function money(v) {
        if (v === null || v === undefined) return '-';
        return '¥' + Number(v).toFixed(2);
    }

    function fmtTime(s) {
        if (!s) return '-';
        return String(s).replace('T', ' ').substring(0, 19);
    }

    function uuid() {
        if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
        return 'bid-' + Date.now() + '-' + Math.random().toString(16).slice(2);
    }

    function post(url, body) {
        return window.apiFetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: body === undefined ? undefined : JSON.stringify(body)
        }).then(readResult);
    }

    function get(url) {
        return window.apiFetch(url, { method: 'GET' }).then(readResult);
    }

    /** 统一解析 Result 包装：code !== 200 一律当失败抛出，避免把错误当数据渲染。 */
    function readResult(res) {
        return res.json().then(function (body) {
            if (!body || body.code !== 200) {
                var err = new Error((body && body.message) || ('请求失败(' + res.status + ')'));
                err.needLogin = res.status === 401;
                throw err;
            }
            return body.data;
        });
    }

    function fail(e) {
        if (e && e.needLogin) return;           // 登录框已由 auth.js 弹出
        if (e && e.__needLogin) return;
        toast((e && e.message) || '操作失败');
    }

    // ==================== 列表 ====================

    function badgeClass(status) {
        if (status === 'ACTIVE') return 'active';
        if (status === 'SCHEDULED') return 'scheduled';
        return 'closed';
    }

    function countdownText(seconds) {
        if (seconds <= 0) return '已到点';
        var d = Math.floor(seconds / 86400);
        var h = Math.floor((seconds % 86400) / 3600);
        var m = Math.floor((seconds % 3600) / 60);
        var s = seconds % 60;
        if (d > 0) return d + ' 天 ' + h + ' 小时';
        if (h > 0) return h + ' 小时 ' + m + ' 分';
        return m + ' 分 ' + s + ' 秒';
    }

    function loadItems() {
        var box = $('items');
        box.innerHTML = '<div class="loading">加载中…</div>';
        $('empty').style.display = 'none';

        get('/api/auction/items?status=' + encodeURIComponent(state.status) + '&page=1&size=24')
            .then(function (page) {
                state.items = page.content || [];
                renderItems();
            })
            .catch(function (e) {
                box.innerHTML = '';
                fail(e);
            });
    }

    function renderItems() {
        var box = $('items');
        var items = state.items;
        if (!items.length) {
            box.innerHTML = '';
            $('empty').style.display = 'block';
            return;
        }
        box.innerHTML = items.map(function (it) {
            var label = it.status === 'ACTIVE' ? '距结束 ' : (it.status === 'SCHEDULED' ? '距开拍 ' : '');
            return '' +
                '<div class="card" data-id="' + it.id + '">' +
                '  <div class="card-top">' +
                '    <div class="card-title">' + escapeHtml(it.title) + '</div>' +
                '    <span class="badge ' + badgeClass(it.status) + '">' + escapeHtml(it.statusText || it.status) + '</span>' +
                '  </div>' +
                '  <div class="price">' + money(it.currentPrice) +
                '    <small>保证金 ' + money(it.deposit) + '</small></div>' +
                '  <div class="meta">' +
                '    <span>' + (it.leaderName ? ('领先：' + escapeHtml(it.leaderName)) : '暂无出价') + '</span>' +
                '    <span class="countdown" data-seconds="' + (it.remainingSeconds || 0) + '">' +
                label + countdownText(it.remainingSeconds || 0) + '</span>' +
                '  </div>' +
                '</div>';
        }).join('');

        Array.prototype.forEach.call(box.querySelectorAll('.card'), function (card) {
            card.addEventListener('click', function () { openDetail(card.getAttribute('data-id')); });
        });
        startTicker();
    }

    function escapeHtml(s) {
        return String(s === null || s === undefined ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    /** 倒计时按秒递减；不做服务端对时（服务端已给 remainingSeconds）。 */
    function startTicker() {
        if (startTicker.timer) clearInterval(startTicker.timer);
        startTicker.timer = setInterval(function () {
            Array.prototype.forEach.call(document.querySelectorAll('.countdown'), function (el) {
                var left = parseInt(el.getAttribute('data-seconds'), 10) - 1;
                if (isNaN(left)) return;
                el.setAttribute('data-seconds', left);
                var prefix = el.textContent.indexOf('距开拍') === 0 ? '距开拍 ' : '距结束 ';
                el.textContent = prefix + countdownText(left);
            });
        }, 1000);
    }

    // ==================== 详情 ====================

    function openDetail(id) {
        get('/api/auction/items/' + id).then(function (d) {
            state.detail = d;
            $('dTitle').textContent = d.title;
            $('dSub').textContent = '编号 ' + d.itemNo + ' · ' + (d.statusText || d.status);
            $('dPrice').textContent = money(d.currentPrice);
            $('dStart').textContent = money(d.startPrice);
            $('dIncrement').textContent = money(d.bidIncrement);
            $('dDeposit').textContent = money(d.deposit);
            $('dLeader').textContent = d.leaderName || '暂无';
            $('dEnd').textContent = fmtTime(d.endTime);
            $('dBidCount').textContent = d.bidCount + ' 次';

            var min = Number(d.currentPrice) + Number(d.bidIncrement);
            $('dMin').textContent = min.toFixed(2);
            $('bidAmount').value = min.toFixed(2);
            $('bidAmount').min = min.toFixed(2);

            var canBid = d.status === 'ACTIVE';
            $('bidBox').style.display = canBid ? 'block' : 'none';
            $('dBidHint').textContent = '';
            $('enrollBox').style.display = 'none';
            $('dEnrollHint').textContent = '';

            $('drawerMask').classList.add('open');
            checkDeal(d);
        }).catch(fail);
    }

    /** 已成交的拍品：若当前用户是买家且未付款，给出付款入口。 */
    function checkDeal(item) {
        $('payBox').style.display = 'none';
        if (item.status !== 'SETTLED') return;

        get('/api/auction/items/' + item.id + '/deal').then(function (deal) {
            state.deal = deal;
            var me = window.currentUser;
            var isBuyer = me && deal && String(deal.status) === 'PENDING_PAYMENT';
            if (isBuyer) $('payBox').style.display = 'block';
        }).catch(function () { /* 无成交单或未登录：不展示付款入口即可 */ });
    }

    function closeDrawer() {
        $('drawerMask').classList.remove('open');
    }

    // ==================== 出价 ====================

    function submitBid() {
        var d = state.detail;
        if (!d) return;
        var amount = parseFloat($('bidAmount').value);
        if (!amount || amount <= 0) { toast('请输入有效金额'); return; }

        var btn = $('bidBtn');
        btn.disabled = true;
        post('/api/auction/items/' + d.id + '/bids', { amount: amount, idempotencyKey: uuid() })
            .then(function (r) {
                toast(r.message || (r.accepted ? '出价成功' : '出价未通过'));
                if (!r.accepted) {
                    // 未缴保证金是可恢复的拒绝：直接亮出报名入口，不让用户去猜
                    if (r.outcome === 'DEPOSIT_REQUIRED') {
                        $('enrollBox').style.display = 'block';
                        $('dEnrollHint').textContent = '缴纳后即可参与竞拍';
                    }
                    $('dBidHint').textContent = r.message || '';
                    $('dBidHint').className = 'hint warn';
                    return;
                }
                loadItems();
                openDetail(d.id);
            })
            .catch(fail)
            .then(function () { btn.disabled = false; });
    }

    // ==================== 保证金 ====================

    function enroll() {
        var d = state.detail;
        if (!d) return;
        $('enrollBtn').disabled = true;
        post('/api/auction/items/' + d.id + '/enroll')
            .then(function (ticket) {
                toast('已创建支付单 ' + ticket.payNo);
                // 演示/联调：直接模拟支付成功。真实环境应跳转 ticket.checkoutUrl，
                // 由网关回调推进状态（后端已有 /mock-pay/** 与生产开关）。
                return post('/api/auction/mock-pay/' + encodeURIComponent(ticket.payNo) + '/success');
            })
            .then(function () {
                toast('保证金已缴纳，可以出价了');
                $('enrollBox').style.display = 'none';
                $('bidBox').style.display = 'block';
                loadMyList();
            })
            .catch(fail)
            .then(function () { $('enrollBtn').disabled = false; });
    }

    // ==================== 成交款 ====================

    function payDeal() {
        var deal = state.deal;
        if (!deal) return;
        $('payDealBtn').disabled = true;
        post('/api/auction/deals/' + deal.id + '/pay')
            .then(function (ticket) {
                toast('已创建支付单 ' + ticket.payNo);
                return post('/api/auction/mock-pay/' + encodeURIComponent(ticket.payNo) + '/success');
            })
            .then(function () {
                toast('成交款已支付，保证金将原路退回');
                closeDrawer();
                loadMyList();
            })
            .catch(fail)
            .then(function () { $('payDealBtn').disabled = false; });
    }

    // ==================== 我的参拍 ====================

    var DEPOSIT_HINT = {
        PENDING_PAY: '待支付', PAY_FAILED: '支付失败', CLOSED: '已关闭',
        HELD: '已冻结', REFUNDING: '退款中', REFUNDED: '已退回',
        REFUND_DEAD: '退款异常', FORFEITED: '已罚没'
    };
    var DEAL_HINT = {
        PENDING_PAYMENT: '待支付', PAID: '已支付', COMPLETED: '已完成', CANCELLED: '已取消'
    };

    function pillClass(text) {
        if (!text) return 'pill';
        if (text.indexOf('已退回') >= 0 || text.indexOf('已支付') >= 0 || text.indexOf('已完成') >= 0) return 'pill ok';
        if (text.indexOf('罚没') >= 0 || text.indexOf('异常') >= 0 || text.indexOf('失败') >= 0) return 'pill err';
        if (text.indexOf('待支付') >= 0 || text.indexOf('退款中') >= 0 || text.indexOf('冻结') >= 0) return 'pill warn';
        return 'pill';
    }

    function loadMyList() {
        var box = $('myList');
        if (!window.currentUser) {
            box.innerHTML = '<div class="loading">登录后可查看</div>';
            return;
        }
        if (state.myMode === 'notify') {
            loadNotifications(box);
            return;
        }

        var url = state.myMode === 'deposit' ? '/api/auction/deposits/mine' : '/api/auction/deals/mine';
        box.innerHTML = '<div class="loading">加载中…</div>';

        get(url).then(function (rows) {
            rows = rows || [];
            if (!rows.length) {
                box.innerHTML = '<div class="loading">暂无记录</div>';
                return;
            }
            box.innerHTML = rows.map(function (r) {
                if (state.myMode === 'deposit') {
                    var text = DEPOSIT_HINT[r.status] || r.status;
                    return '<div class="list-row">' +
                        '<div class="grow"><div class="t">' + escapeHtml(r.itemTitle || ('拍品 ' + r.itemId)) + '</div>' +
                        '<div class="s">保证金 ' + money(r.amount) + ' · ' + (r.lastError ? escapeHtml(r.lastError) : '') + '</div></div>' +
                        '<span class="' + pillClass(text) + '">' + text + '</span>' +
                        '</div>';
                }
                var t2 = DEAL_HINT[r.status] || r.status;
                var action = r.status === 'PENDING_PAYMENT'
                    ? '<button class="btn" data-pay="' + r.id + '" style="padding:6px 12px;font-size:13px">去支付</button>'
                    : '';
                return '<div class="list-row">' +
                    '<div class="grow"><div class="t">' + escapeHtml(r.itemTitle || ('拍品 ' + r.itemId)) + '</div>' +
                    '<div class="s">成交价 ' + money(r.dealPrice) + ' · 截止 ' + fmtTime(r.payDeadline) + '</div></div>' +
                    '<span class="' + pillClass(t2) + '">' + t2 + '</span>' + action +
                    '</div>';
            }).join('');

            Array.prototype.forEach.call(box.querySelectorAll('[data-pay]'), function (btn) {
                btn.addEventListener('click', function () {
                    payDealById(btn.getAttribute('data-pay'));
                });
            });
        }).catch(function (e) {
            box.innerHTML = '<div class="loading">登录后可查看</div>';
            fail(e);
        });
    }

    // ==================== 我的消息 ====================

    /**
     * 消息列表。
     *
     * 与保证金/成交不同，这个接口返回的是分页对象（content/…）而不是数组，
     * 所以单独走一条加载路径，不硬塞进上面的分支里。
     */
    function loadNotifications(box) {
        get('/api/auction/notifications/mine?page=1&size=50').then(function (page) {
            var rows = (page && page.content) || [];
            loadUnread();
            if (!rows.length) {
                box.innerHTML = '<div class="loading">暂无消息</div>';
                return;
            }
            box.innerHTML = rows.map(function (n) {
                var unread = !n.readFlag;
                return '<div class="list-row' + (unread ? ' unread' : '') + '" data-notify="' + n.id + '">' +
                    '<div class="grow">' +
                    '<div class="t">' + (unread ? '<span class="unread-dot"></span>' : '') +
                    escapeHtml(n.title) + ' <span class="pill">' + escapeHtml(n.typeText || '') + '</span></div>' +
                    '<div class="s">' + escapeHtml(n.content) + '</div>' +
                    '<div class="s">' + fmtTime(n.createTime) + '</div>' +
                    '</div></div>';
            }).join('');

            Array.prototype.forEach.call(box.querySelectorAll('[data-notify]'), function (row) {
                row.addEventListener('click', function () {
                    if (!row.classList.contains('unread')) return;
                    var id = row.getAttribute('data-notify');
                    post('/api/auction/notifications/' + id + '/read').then(function () {
                        row.classList.remove('unread');
                        var dot = row.querySelector('.unread-dot');
                        if (dot) dot.remove();
                        loadUnread();
                    }).catch(fail);
                });
            });
        }).catch(function (e) {
            box.innerHTML = '<div class="loading">登录后可查看</div>';
            fail(e);
        });
    }

    /** 未读角标。失败静默：角标拿不到不应该在页面上弹错误。 */
    function loadUnread() {
        if (!window.currentUser) return;
        get('/api/auction/notifications/unread-count').then(function (n) {
            var badge = $('notifyBadge');
            if (!badge) return;
            var count = Number(n) || 0;
            if (count > 0) {
                badge.textContent = count > 99 ? '99+' : count;
                badge.style.display = 'inline-block';
            } else {
                badge.style.display = 'none';
            }
        }).catch(function () { /* 静默 */ });
    }

    /** 从"我的成交"直接付款：先取详情拿 itemId，再复用 payDeal 流程。 */    function payDealById(dealId) {
        post('/api/auction/deals/' + dealId + '/pay')
            .then(function (ticket) {
                toast('已创建支付单 ' + ticket.payNo);
                return post('/api/auction/mock-pay/' + encodeURIComponent(ticket.payNo) + '/success');
            })
            .then(function () {
                toast('成交款已支付');
                loadMyList();
            })
            .catch(fail);
    }

    // ==================== 绑定 ====================

    function bind() {
        Array.prototype.forEach.call(document.querySelectorAll('#tabs .tab'), function (tab) {
            tab.addEventListener('click', function () {
                Array.prototype.forEach.call(document.querySelectorAll('#tabs .tab'), function (t) {
                    t.classList.remove('active');
                });
                tab.classList.add('active');
                state.status = tab.getAttribute('data-status');
                loadItems();
            });
        });

        $('myDepositTab').addEventListener('click', function () {
            state.myMode = 'deposit';
            $('myDepositTab').classList.add('active');
            $('myDealTab').classList.remove('active');
            $('myNotifyTab').classList.remove('active');
            loadMyList();
        });
        $('myDealTab').addEventListener('click', function () {
            state.myMode = 'deal';
            $('myDealTab').classList.add('active');
            $('myDepositTab').classList.remove('active');
            $('myNotifyTab').classList.remove('active');
            loadMyList();
        });
        $('myNotifyTab').addEventListener('click', function () {
            state.myMode = 'notify';
            $('myNotifyTab').classList.add('active');
            $('myDepositTab').classList.remove('active');
            $('myDealTab').classList.remove('active');
            loadMyList();
        });

        $('bidBtn').addEventListener('click', submitBid);
        $('enrollBtn').addEventListener('click', enroll);
        $('payDealBtn').addEventListener('click', payDeal);
        $('closeDrawer').addEventListener('click', closeDrawer);
        $('drawerMask').addEventListener('click', function (e) {
            if (e.target === $('drawerMask')) closeDrawer();
        });
    }

    window.addEventListener('DOMContentLoaded', function () {
        bind();
        loadItems();
        // auth.js 会在 DOMContentLoaded 拉取登录态，这里延后一拍再读
        setTimeout(loadMyList, 600);
        setTimeout(loadUnread, 600);

        // 支持 /auction?item=123 直接打开详情：后台"查看"、分享链接都靠它，
        // 否则点过去只会落在列表首屏，还得自己再找一次。
        var target = new URLSearchParams(window.location.search).get('item');
        if (target && /^\d+$/.test(target)) {
            openDetail(target);
        }
    });
})();
