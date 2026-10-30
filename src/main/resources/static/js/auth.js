/**
 * 【功能】全局登录态 + 弹窗 + apiFetch 包装器。
 *
 * 【登录态三源对齐】页面里的登录态原本有三份来源：
 *   1. HttpSession（服务端真源，/api/user/current 反映的就是它）
 *   2. window.currentUser（本文件维护的客户端副本）
 *   3. 服务端渲染进 HTML 的快照（如 seat.html 的"请先登录"、后台菜单）
 *   本文件负责 2、3 与 1 保持一致：
 *   - refreshCurrentUser() 每次刷新都同步 2 并重绘 3；
 *   - onLoginSuccess() 在没有待重试请求时整页刷新，让 3 重新由服务端渲染。
 *
 * 【暴露】
 *   window.currentUser
 *   window.openLoginModal() / closeLoginModal()
 *   window.openBindModal() / closeBindModal()
 *   window.apiFetch(url, opts)
 *   window.doLogout()
 *   window.refreshCurrentUser()
 *
 * 【模板约定】需要跟随登录态显隐的区块加属性：
 *   data-auth-guest       未登录时显示
 *   data-auth-user        已登录时显示
 *   data-auth-username    已登录时填入当前用户名
 */

(function () {
    'use strict';

    // ==================== 登录态 ====================
    window.currentUser = null;

    window.refreshCurrentUser = function () {
        return fetch('/api/user/current', { credentials: 'same-origin' })
            .then(r => r.json())
            .then(res => {
                window.currentUser = (res.code === 200) ? res.data : null;
                updateNavUI();
                applyAuthVisibility();
                return window.currentUser;
            })
            .catch(() => {
                window.currentUser = null;
                updateNavUI();
                applyAuthVisibility();
                return null;
            });
    };

    /**
     * 让服务端渲染的登录态区块跟随客户端登录态。
     * 会话过期后页面正文还写着"已登录：xxx"、或登录成功后正文仍是"请先登录"，
     * 都是因为这块 HTML 只在页面加载时渲染一次。
     */
    function applyAuthVisibility() {
        const logged = !!window.currentUser;

        document.querySelectorAll('[data-auth-guest]').forEach(el => {
            el.style.display = logged ? 'none' : '';
        });

        document.querySelectorAll('[data-auth-user]').forEach(el => {
            el.style.display = logged ? '' : 'none';
            if (!logged) return;
            el.querySelectorAll('[data-auth-username]').forEach(nameEl => {
                nameEl.textContent = window.currentUser.username || '';
            });
        });
    }

    // ==================== 顶部导航 ====================
    function updateNavUI() {
        const navRight = document.querySelector('.nav-right .user-area');
        if (!navRight) return;

        if (window.currentUser) {
            const role = window.currentUser.role || 'USER';

            // ★ 只有 ADMIN 才显示"后台管理"
            const adminLink = (role === 'ADMIN')
                ? '<a href="/admin">后台管理</a>'
                : '';

            navRight.innerHTML =
                '<a href="/orders" style="color:#1e3a8a;margin-right:16px;font-size:14px;">🎟️ 我的订单</a>' +
                '<div class="user-dropdown">' +
                    '<span class="user-name">👤 ' + escapeHtml(window.currentUser.username) + ' ▼</span>' +
                    '<div class="dropdown-menu">' +
                        '<a href="/user/profile">个人中心</a>' +
                        '<a href="/orders">我的订单</a>' +
                        adminLink +
                        '<a href="javascript:void(0)" onclick="openBindModal()">绑定账号</a>' +
                        '<a href="javascript:void(0)" onclick="doLogout()">退出登录</a>' +
                    '</div>' +
                '</div>';
        } else {
            navRight.innerHTML =
                '<a href="javascript:void(0)" onclick="openLoginModal()" ' +
                'style="color:#1e3a8a;font-weight:bold;font-size:14px;">登录 / 注册</a>';
        }
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, c => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[c]));
    }

    // ==================== 登录模态框 ====================
    let afterLoginCallback = null;
    /** 是否已有一个登录框在等用户操作，用于阻止并发 401 反复弹窗/反复排队。 */
    let loginPending = false;

    window.openLoginModal = function (callback) {
        afterLoginCallback = callback || null;
        const modal = document.getElementById('globalLoginModal');
        if (!modal) {
            sessionStorage.setItem('loginBack', window.location.pathname);
            window.location.href = '/login';
            return;
        }
        modal.classList.add('show');
        setTimeout(() => {
            const input = document.getElementById('glmPwdUsername');
            if (input) input.focus();
        }, 100);
    };

    window.closeLoginModal = function () {
        const modal = document.getElementById('globalLoginModal');
        if (modal) modal.classList.remove('show');
        afterLoginCallback = null;
        loginPending = false;
    };

    function onLoginSuccess(user) {
        window.currentUser = user;
        updateNavUI();
        applyAuthVisibility();
        loginPending = false;
        const modal = document.getElementById('globalLoginModal');
        if (modal) modal.classList.remove('show');

        // 有等待中的请求（如被 401 拦下的下单）→ 先让它重试一次，由调用方决定后续跳转
        if (typeof afterLoginCallback === 'function') {
            const cb = afterLoginCallback;
            afterLoginCallback = null;
            cb();
            return;
        }

        // 没有待重试请求 → 整页刷新，让服务端按新会话重新渲染页面快照
        //（与 /login 独立页登录后跳转的行为保持一致）
        window.location.reload();
    }

    // ==================== 防重放头 ====================
    function newNonce() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') {
            return window.crypto.randomUUID();
        }
        return Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
    }

    /** 补齐 X-Timestamp / X-Nonce，已有则保留调用方自己的值。 */
    function withAntiReplayHeaders(headers) {
        const merged = Object.assign({}, headers || {});
        const has = name => Object.keys(merged).some(k => k.toLowerCase() === name);
        if (!has('x-timestamp')) merged['X-Timestamp'] = String(Date.now());
        if (!has('x-nonce')) merged['X-Nonce'] = newNonce();
        return merged;
    }

    // ==================== apiFetch ====================
    window.apiFetch = function (url, options) {
        options = options || {};
        options.credentials = 'same-origin';

        // 写操作补防重放头：/api/orders/** 的 POST/PUT/DELETE 由 AntiReplayInterceptor 校验，
        // 网页端此前不带这两个头，直接被 400 拒（外部客户端带了 → 出现"接口能下单、网页不能"）。
        const method = (options.method || 'GET').toUpperCase();
        if (method !== 'GET' && method !== 'HEAD') {
            options.headers = withAntiReplayHeaders(options.headers);
        }

        return fetch(url, options).then(res => {
            if (res.status !== 401) return res;

            // 401 也可能只是页面快照过期：先向服务端确认一次，
            // 确认仍未登录才弹登录框，避免"明明已登录还弹登录框"。
            return window.refreshCurrentUser().then(user => {
                if (user) {
                    // 会话有效，原样重试一次（用原生 fetch，不递归 apiFetch，不会无限重试）
                    return fetch(url, options);
                }
                return new Promise((resolve, reject) => {
                    if (loginPending) {
                        reject({ __needLogin: true, message: '未登录' });
                        return;
                    }
                    loginPending = true;
                    window.openLoginModal(function () {
                        loginPending = false;
                        // 登录成功后只重试一次，失败就交给调用方提示，不再触发第二轮弹窗
                        fetch(url, options).then(resolve).catch(reject);
                    });
                    reject({ __needLogin: true, message: '未登录' });
                });
            });
        });
    };

    // ==================== 退出登录 ====================
    window.doLogout = function () {
        if (!confirm('确定要退出登录吗？')) return;
        fetch('/api/user/logout', { method: 'POST', credentials: 'same-origin' })
            .then(() => { window.location.href = '/'; })
            .catch(() => { window.location.href = '/'; });
    };

    // ==================== 登录模态框事件 ====================
    function bindLoginModalEvents() {
        const modal = document.getElementById('globalLoginModal');
        if (!modal) return;

        const tabSms = document.getElementById('glmTabSms');
        const tabPwd = document.getElementById('glmTabPwd');
        const panelSms = document.getElementById('glmPanelSms');
        const panelPwd = document.getElementById('glmPanelPwd');
        const msg = document.getElementById('glmMsg');

        function showMsg(text, type) {
            msg.textContent = text;
            msg.className = 'glm-msg ' + (type || '');
        }

        tabSms.addEventListener('click', () => {
            tabSms.classList.add('active'); tabPwd.classList.remove('active');
            panelSms.classList.add('active'); panelPwd.classList.remove('active');
            msg.className = 'glm-msg';
        });
        tabPwd.addEventListener('click', () => {
            tabPwd.classList.add('active'); tabSms.classList.remove('active');
            panelPwd.classList.add('active'); panelSms.classList.remove('active');
            msg.className = 'glm-msg';
        });

        // ===== 账号密码登录 =====
        const pwdUser = document.getElementById('glmPwdUsername');
        const pwdPass = document.getElementById('glmPwdPassword');
        const pwdBtn = document.getElementById('glmPwdLoginBtn');

        function checkPwd() { pwdBtn.disabled = !(pwdUser.value.trim() && pwdPass.value.trim()); }
        pwdUser.addEventListener('input', checkPwd);
        pwdPass.addEventListener('input', checkPwd);

        pwdBtn.addEventListener('click', () => {
            pwdBtn.disabled = true; pwdBtn.textContent = '登录中...';
            fetch('/api/user/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                body: JSON.stringify({
                    username: pwdUser.value.trim(),
                    password: pwdPass.value.trim()
                })
            })
            .then(r => r.json())
            .then(res => {
                if (res.code !== 200) throw new Error(res.message);
                onLoginSuccess(res.data);
            })
            .catch(err => {
                showMsg(err.message || '登录失败', 'error');
                pwdBtn.disabled = false; pwdBtn.textContent = '登录';
            });
        });

        // ===== 短信登录 =====
        const smsPhone = document.getElementById('glmSmsPhone');
        const smsCode = document.getElementById('glmSmsCode');
        const sendBtn = document.getElementById('glmSendBtn');
        const smsBtn = document.getElementById('glmSmsLoginBtn');
        let countdown = 0, timer = null;

        function checkSend() {
            const v = /^1[3-9]\d{9}$/.test(smsPhone.value.trim());
            sendBtn.disabled = !(v && countdown === 0);
            sendBtn.textContent = countdown > 0 ? countdown + 's' : '发送验证码';
        }
        function checkSms() {
            const vp = /^1[3-9]\d{9}$/.test(smsPhone.value.trim());
            const vc = /^\d{6}$/.test(smsCode.value.trim());
            smsBtn.disabled = !(vp && vc);
        }
        smsPhone.addEventListener('input', () => { checkSend(); checkSms(); });
        smsCode.addEventListener('input', checkSms);

        sendBtn.addEventListener('click', () => {
            const phone = smsPhone.value.trim();
            if (!/^1[3-9]\d{9}$/.test(phone)) { showMsg('手机号格式错误', 'error'); return; }
            sendBtn.disabled = true;

            fetch('/api/user/send-sms', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                body: JSON.stringify({ phone })
            })
            .then(r => r.json())
            .then(res => {
                if (res.code !== 200) throw new Error(res.message);

                // 接口契约：演示环境 data=6 位验证码（自动填入）；生产环境 data=null（只剩短信）
                const code = res.data;
                if (code && /^\d{6}$/.test(String(code))) {
                    smsCode.value = code;
                    checkSms();
                    showMsg('【演示环境】验证码已自动填入', 'success');
                } else {
                    showMsg('验证码已发送，请查收短信', 'success');
                }

                countdown = 60;
                checkSend();
                timer = setInterval(() => {
                    countdown--;
                    checkSend();
                    if (countdown <= 0) { clearInterval(timer); checkSend(); }
                }, 1000);
            })
            .catch(err => { showMsg(err.message || '发送失败', 'error'); sendBtn.disabled = false; });
        });

        smsBtn.addEventListener('click', () => {
            smsBtn.disabled = true; smsBtn.textContent = '登录中...';
            fetch('/api/user/login-sms', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                body: JSON.stringify({
                    phone: smsPhone.value.trim(),
                    code: smsCode.value.trim()
                })
            })
            .then(r => r.json())
            .then(res => {
                if (res.code !== 200) throw new Error(res.message);
                onLoginSuccess(res.data);
            })
            .catch(err => {
                showMsg(err.message || '登录失败', 'error');
                smsBtn.disabled = false; smsBtn.textContent = '登录 / 注册';
            });
        });

        modal.addEventListener('click', e => {
            if (e.target === modal) window.closeLoginModal();
        });
    }

    // ==================== 绑定账号弹窗 ====================
    window.openBindModal = function () {
        const modal = document.getElementById('bindAccountModal');
        if (!modal) { alert('绑定弹窗未加载'); return; }
        document.getElementById('bindUsername').value = '';
        document.getElementById('bindPassword').value = '';
        document.getElementById('bindConfirm').value = '';
        document.getElementById('bindMsg').className = 'glm-msg';
        modal.classList.add('show');
    };

    window.closeBindModal = function () {
        const modal = document.getElementById('bindAccountModal');
        if (modal) modal.classList.remove('show');
    };

    function bindBindModalEvents() {
        const modal = document.getElementById('bindAccountModal');
        if (!modal) return;

        const bindUser = document.getElementById('bindUsername');
        const bindPwd = document.getElementById('bindPassword');
        const bindConfirm = document.getElementById('bindConfirm');
        const bindMsg = document.getElementById('bindMsg');
        const bindBtn = document.getElementById('bindSubmitBtn');

        function showBindMsg(text, type) {
            bindMsg.textContent = text;
            bindMsg.className = 'glm-msg ' + (type || '');
        }

        bindBtn.addEventListener('click', function () {
            const username = bindUser.value.trim();
            const password = bindPwd.value.trim();
            const confirm = bindConfirm.value.trim();

            if (!username || username.length < 2 || username.length > 20) {
                showBindMsg('用户名长度 2~20 位', 'error'); return;
            }
            const PWD_REGEX = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[!@#$%^&*._-])[A-Za-z\d!@#$%^&*._-]{8,20}$/;
            if (!PWD_REGEX.test(password)) {
                showBindMsg('密码必须包含大小写字母、数字、特殊字符，长度 8~20 位', 'error'); return;
            }
            if (password !== confirm) {
                showBindMsg('两次输入的密码不一致', 'error'); return;
            }

            bindBtn.disabled = true;
            bindBtn.textContent = '绑定中...';

            fetch('/api/user/bind-account', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                body: JSON.stringify({ username: username, password: password })
            })
            .then(r => r.json())
            .then(res => {
                if (res.code !== 200) throw new Error(res.message);
                showBindMsg('绑定成功，正在刷新...', 'success');
                setTimeout(() => { window.location.reload(); }, 800);
            })
            .catch(err => {
                showBindMsg(err.message || '绑定失败', 'error');
                bindBtn.disabled = false;
                bindBtn.textContent = '确认绑定';
            });
        });

        modal.addEventListener('click', e => {
            if (e.target === modal) window.closeBindModal();
        });
    }

    // ==================== 启动 ====================
    document.addEventListener('DOMContentLoaded', function () {
        window.refreshCurrentUser();
        bindLoginModalEvents();
        bindBindModalEvents();
    });
})();