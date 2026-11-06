/* 滑块自动通过（纯 JS，供 WebView 注入使用）
 * 返回 Promise<{ok:boolean, gap?:number, dist?:number, reason?:string}>
 */
(function () {
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  function loadImg(src) {
    return new Promise(function (res, rej) {
      var im = new Image();
      im.onload = function () { res(im); };
      im.onerror = function () { rej(new Error('img load fail')); };
      im.src = src;
    });
  }

  function pixels(img) {
    var c = document.createElement('canvas');
    c.width = img.naturalWidth; c.height = img.naturalHeight;
    var x = c.getContext('2d');
    x.drawImage(img, 0, 0);
    return x.getImageData(0, 0, c.width, c.height);
  }

  function findGap(bigSrc, smallSrc, divW) {
    return Promise.all([loadImg(bigSrc), loadImg(smallSrc)]).then(function (a) {
      var big = pixels(a[0]), sm = pixels(a[1]);
      var W = big.width, H = big.height, sw = sm.width, sh = sm.height;
      var bd = big.data, sd = sm.data;

      // 拼图块掩码：非透明且非纯白
      var mask = new Uint8Array(sw * sh);
      var minX = sw, maxX = -1, minY = sh, maxY = -1;
      for (var y = 0; y < sh; y++) {
        for (var x = 0; x < sw; x++) {
          var i = (y * sw + x) * 4;
          var al = sd[i + 3], r = sd[i], g = sd[i + 1], b = sd[i + 2];
          var white = r > 245 && g > 245 && b > 245;
          if (al > 40 && !white) {
            mask[y * sw + x] = 1;
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
          }
        }
      }
      if (maxX < 0) return null;
      var pw = maxX - minX + 1;

      // 形状边缘点
      var ex = [], ey = [];
      for (var yy = minY + 1; yy < maxY; yy++) {
        for (var xx = minX + 1; xx < maxX; xx++) {
          if (!mask[yy * sw + xx]) continue;
          if (!(mask[(yy - 1) * sw + xx] && mask[(yy + 1) * sw + xx] &&
                mask[yy * sw + xx - 1] && mask[yy * sw + xx + 1])) {
            ex.push(xx - minX); ey.push(yy);
          }
        }
      }
      if (!ex.length) return null;

      // 背景亮度梯度
      var lum = new Float32Array(W * H);
      for (var p = 0; p < W * H; p++) {
        var q = p * 4;
        lum[p] = bd[q] * 0.299 + bd[q + 1] * 0.587 + bd[q + 2] * 0.114;
      }
      function G(x, y) {
        if (x <= 0 || y <= 0 || x >= W - 1 || y >= H - 1) return 0;
        var gx = lum[y * W + x + 1] - lum[y * W + x - 1];
        var gy = lum[(y + 1) * W + x] - lum[(y - 1) * W + x];
        return Math.sqrt(gx * gx + gy * gy);
      }

      var best = -1, bestX = 0;
      var limit = W - pw;
      for (var sx = 0; sx < limit; sx++) {
        var s = 0;
        for (var k = 0; k < ex.length; k++) s += G(sx + ex[k], ey[k]);
        if (s > best) { best = s; bestX = sx; }
      }
      var scale = divW / W;
      return { gap: bestX, dist: (bestX - minX) * scale };
    });
  }

  function fire(el, type, x, y) {
    var ev = new MouseEvent(type, {
      bubbles: true, cancelable: true, view: window,
      clientX: x, clientY: y, button: 0,
      buttons: type === 'mouseup' ? 0 : 1
    });
    el.dispatchEvent(ev);
  }

  function drag(distance) {
    var h = document.querySelector('#sliderDiv .slider');
    if (!h) return Promise.resolve(false);
    var r = h.getBoundingClientRect();
    var x0 = r.x + r.width / 2, y0 = r.y + r.height / 2;
    var steps = 30, cur = 0, baseY = (Math.random() - 0.5) * 2;
    fire(h, 'mousedown', x0, y0);
    var chain = Promise.resolve();
    for (var i = 1; i <= steps; i++) {
      (function (i) {
        chain = chain.then(function () {
          var t = i / steps;
          var want = distance * (1 - Math.pow(1 - t, 3));
          if (want - cur < 0.6 && i < steps) return null;
          cur = want;
          fire(document, 'mousemove', x0 + cur, y0 + baseY + (Math.random() - 0.5) * 4);
          return sleep(12 + Math.random() * 22);
        });
      })(i);
    }
    chain = chain.then(function () { return sleep(60 + Math.random() * 80); });
    chain = chain.then(function () { fire(document, 'mouseup', x0 + distance, y0 + baseY); });
    return chain.then(function () { return true; });
  }

  window.__solveSlider = function () {
    var big = document.querySelector('#slider-img1');
    var sm = document.querySelector('#slider-img2');
    var dv = document.querySelector('#sliderDiv');
    if (!big || !sm || !dv) return Promise.resolve({ ok: false, reason: 'no-slider' });
    if (!big.src || big.src.length < 200) return Promise.resolve({ ok: false, reason: 'no-img' });

    return findGap(big.src, sm.src, dv.offsetWidth).then(function (f) {
      if (!f) return { ok: false, reason: 'no-gap' };
      if (!(f.dist > 4 && f.dist < 233)) return { ok: false, reason: 'bad-gap', gap: f.gap };
      return drag(f.dist).then(function () {
        return sleep(1200);
      }).then(function () {
        var c = document.querySelector('.sliderContainer');
        var bj = document.querySelector('#bjGray');
        var passed = (c && c.className.indexOf('success') >= 0) ||
                     (bj && bj.style.display === 'none');
        return { ok: !!passed, gap: f.gap, dist: f.dist };
      });
    });
  };

  /* 把学号/密码填进表单并切到「账号登录」标签 */
  window.__fillLogin = function (user, pwd) {
    var tabs = document.querySelectorAll("a.loginFont_a");
    for (var i = 0; i < tabs.length; i++) {
      if ((tabs[i].textContent || '').indexOf('账号登录') >= 0) { tabs[i].click(); break; }
    }
    return sleep(400).then(function () {
      var u = document.querySelector('#username');
      var p = document.querySelector('#password');
      if (!u) return { ok: false, reason: 'no-form' };
      u.value = user;
      u.dispatchEvent(new Event('input', { bubbles: true }));
      u.dispatchEvent(new Event('change', { bubbles: true }));
      if (p && pwd) {
        p.value = pwd;
        p.dispatchEvent(new Event('input', { bubbles: true }));
        p.dispatchEvent(new Event('change', { bubbles: true }));
      }
      var rm = document.querySelector('#rememberMe');
      if (rm && !rm.checked) rm.click();
      return { ok: true };
    });
  };
})();

/* ------------------------------------------------------------------
 * 之后追加的两个入口（同一个文件注入到 WebView）
 * ------------------------------------------------------------------ */
(function () {
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  function waitFor(sel, ms) {
    var t0 = Date.now();
    return new Promise(function (res) {
      (function loop() {
        var el = document.querySelector(sel);
        if (el) return res(el);
        if (Date.now() - t0 > ms) return res(null);
        setTimeout(loop, 250);
      })();
    });
  }

  /* 自动登录：填表 -> 点登录 -> 等滑块 -> 自动拖 -> 失败换图重试 */
  window.__autoLogin = function (u, p) {
    if (window.__autoLoginBusy) return Promise.resolve({ ok: false, reason: 'busy' });
    window.__autoLoginBusy = true;
    var t0 = Date.now();
    function step() {
      if (Date.now() - t0 > 45000) return Promise.resolve({ ok: false, reason: 'timeout' });
      var slider = document.querySelector('#sliderDiv');
      var img = document.querySelector('#slider-img1');
      if (slider && img && img.src && img.src.length > 200) {
        return window.__solveSlider().then(function (r) {
          if (r && r.ok) return r;
          var rf = document.querySelector('#sliderDiv .refreshIcon');
          if (rf) rf.click();
          return sleep(1600).then(step);
        });
      }
      return sleep(600).then(step);
    }
    return waitFor('#username', 20000).then(function () {
      return window.__fillLogin(u, p);
    }).then(function () {
      return sleep(350);
    }).then(function () {
      var b = document.querySelector('#login_submit') || document.querySelector('.login-btn');
      if (b) b.click();
      return sleep(1500);
    }).then(step);
  };

  /* 手动登录时顺手把输入的学号/密码交给原生保存（下次自动登录用） */
  window.__watchCreds = function () {
    function grab() {
      var u = document.querySelector('#username');
      var p = document.querySelector('#password') ||
              document.querySelector('#saltPassword') ||
              document.querySelector('input[name=password]');
      if (u && p && u.value && p.value && window.XieHeBridge && XieHeBridge.saveCreds) {
        try { XieHeBridge.saveCreds(u.value, p.value); } catch (e) { }
      }
    }
    document.addEventListener('click', function (e) {
      var t = e.target;
      while (t && t !== document) {
        var cls = t.className ? ('' + t.className) : '';
        if (t.id === 'login_submit' || cls.indexOf('login-btn') >= 0) { grab(); break; }
        t = t.parentNode;
      }
    }, true);
    document.addEventListener('submit', grab, true);
  };
})();
