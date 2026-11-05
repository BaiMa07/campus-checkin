# 接口说明

这份文档记录的是抓包和阅读前端 JS 得到的实际行为，用于二次开发或排查问题。
学校随时可能改接口，改了就按最后一节的方法重新抓一次。

主机 `https://xg.sdxiehe.edu.cn`，应用路径 `/xsfw/sys/swmzncqapp`。

---

## 登录

统一身份认证在 `https://ids.sdxiehe.edu.cn/authserver/login`，service 参数指向查寝首页：

```
https://ids.sdxiehe.edu.cn/authserver/login?service=https%3A%2F%2Fxg.sdxiehe.edu.cn%2Fxsfw%2Fsys%2Fswmzncqapp%2F*default%2Findex.do
```

实测下来有几点需要知道。

密码是前端加密的，AES-CBC，明文是 64 位随机串拼上真实密码，密钥取页面里 `#pwdEncryptSalt` 的 value。

每次登录都强制滑块验证，服务端 `captchaSwitch` 是 2，直接用 HTTP 提交会返回 401
"图形动态码错误"，绕不过去。

滑块用的是 longbow sliderCaptcha。这里有个很容易踩的坑：页面实例上的

```js
i.x = random(...)   // 前端本地随机生成的
```

**是个随机数，不是正确答案**。照着它拖一定失败。正确答案是背景图里被挖掉的拼图形状，
只能从图片里算出来。识别方法见下面"滑块怎么过"。

拖动轨迹还会被前端和服务端一起看：前端判定里 `spliced = (垂直位移标准差 != 0)`，
一路水平直线会被当成机器，所以轨迹必须带自然的垂直抖动。

登录页默认停在微信登录标签页，账号密码输入框是隐藏状态，自动化时要先点一下文字为
"账号登录"的标签把表单切出来。

同一个学号在服务端的会话数量有限制，一边重新登录可能把另一边踢掉。

所以结论是登录必须交给真实浏览器，人工拖一次滑块。拿到 Cookie 之后所有签到请求都能直接用
Cookie 发，不需要再经过浏览器。

需要的 Cookie 是 sdxiehe 域下的全部，包括 JSESSIONID 这类 HttpOnly 的，
所以要从浏览器上下文里读，`document.cookie` 取不到。

---

## 查状态

```
POST /xsfw/sys/swmzncqapp/kqController/getKqInfo.do
Content-Type: application/x-www-form-urlencoded; charset=UTF-8

data={}
```

data 的值是字符串 `{}`，整体要 URL 编码一次。返回结构是 `{"code":"0","data":{...}}`，
注意 code 是字符串不是数字。

data 下面比较有用的字段：

`CONFIG_INFO.KQRQ` 是日期，`CONFIG_INFO.CQKSSJ` 和 `CQJSSJ` 是签到时段的起止（比如 21:30 和 22:30），
`CONFIG_INFO.IN_CQ_TIME` 表示当前是否在时段内，`CONFIG_INFO.IN_DATE` 表示日期是否有效。

`QD_INFO.hasQd` 是今天是否已经签到，`QD_INFO.qdsj` 是签到时间。

`DD_LIST` 是全部打卡点，每一项包含 `QDDD`（名称）、`JDZB`（经度）、`WDZB`（纬度）、
`YXFW`（允许半径，单位米）、`DDDM`（打卡点代码）。

`SSL_INFO.SSLDM_DISPLAY` 是自己的宿舍名称，`SSL_INFO.XSBH` 是学号，`SSL_INFO.XM` 是姓名。

DD_LIST 里同时包含多个校区和若干"辅助"打卡点，命名也不完全统一，比如：

```
山东协和学院(郭店校区)X号公寓     半径 30
山东协和学院(郭店校区)XXXX辅助      半径 50    名称里带"辅助"，匹配时要排除
山东协和学院（遥墙校区）Y号公寓    半径 10    注意这里是全角括号
```

---

## 提交签到

```
POST /xsfw/sys/swmzncqapp/kqController/addKqInfo.do
Content-Type: application/x-www-form-urlencoded; charset=UTF-8

data={"KQWZXX":"山东协和学院(郭店校区)X号公寓","JDZB":"117.278000","WDZB":"36.740000"}
```

data 的值是一段 JSON 字符串，整体 URL 编码。经纬度是字符串形式，不是数字。
字段顺序是 KQWZXX、JDZB、WDZB。

返回 `{"code":"0","msg":"成功"}`。

提交之后一定要再查一次 getKqInfo，确认 `QD_INFO.hasQd` 变成 true 才算真的签上。

### 签到按钮为什么是灰的

前端 dwdk.js 用地图算当前定位到各个打卡点的直线距离，超过该点的 YXFW 米就把按钮置灰，
提示"不在签到范围内"。

所以只要把打卡点坐标直接提交上去，就相当于人在楼下。两个版本都是这么做的，
不需要 GPS 权限，也不需要模拟定位。

---

## 打卡点匹配

用 `SSL_INFO.SSLDM_DISPLAY`（比如"X号公寓"）去匹配 `DD_LIST` 里的 `QDDD`：

先看名称是否包含宿舍名，命中就直接用。没有命中就取宿舍名里的数字（"X号公寓"取 12），
找名称里包含这个数字、且不含"辅助"的打卡点。都没有就报"没匹配到打卡点"。

电脑版的实现在 `pc/core.py` 的 `pick_point()`，安卓版在 `KqModel.java`。

---

## 坐标偏移

提交时给打卡点坐标加 ±3 米左右的随机偏移，避免每次提交的坐标完全一致：

```python
def jitter(lat, lng, meters=3.0):
    import random
    d = meters / 111320.0
    return lat + random.uniform(-d, d), lng + random.uniform(-d, d)
```

偏移量远小于最小的打卡半径（10 米），不影响判定。

---

## 重新抓包

接口变了可以自己再抓一遍。先登录，浏览器配置存在 `pc/data/profiles/acc_*/`，
用 Playwright 打开这个 profile 访问签到页，监听 `page.on("request")`，
过滤掉静态资源，只看 xg.sdxiehe.edu.cn 的 XHR 请求。

也可以在页面上下文里直接 `fetch(...)` 试参数，看返回内容。

注意 headless 模式会被学校的 WAF 拦截，返回的内容是"已被安全设备拦截"，所以抓包必须用有头模式。

---

## 滑块怎么过

登录是绕不开滑块的，但可以自动完成。做法分三步，实现在 `pc/slider.py`。

**第一步，找缺口。** 服务端返回两张图：`bigImage` 是带缺口的背景图，`smallImage` 是拼图块。
拼图块和缺口大小一致，所以把拼图块的**形状边缘**取出来，拿去背景图的**梯度图**上滑动累加，
得分最高的位置就是缺口。这个方法对猫毛、雪地、天空这类画面都很稳，实测误差在 6 像素以内：

```
目测缺口在 x=365，算法给出 x=359
```

**第二步，换算拖动距离。** 图片原始宽 590，显示画布宽 278，比例 0.471。
拖动距离 = (缺口 x - 拼图块左边界) × 比例。

**第三步，拟人拖动。** 用 Playwright 的真实鼠标事件（`isTrusted=true`），轨迹是
easeOutCubic 曲线，每步间隔 30–70 毫秒随机，垂直方向 ±2 像素抖动，中间插一次停顿，
最后回拉对齐。全程约 1 秒。

验证通过时前端会调用 `onSuccess` 自动提交表单，页面直接跳转，所以判断成功的标志是
"页面跳走了"，而不是某个 DOM 元素变化。

连续 8 次测试全部通过，每次 13–20 秒（含启动浏览器）。

另外提一句：headless 模式会被学校的 WAF 拦截，返回"已被安全设备拦截"，
所以自动登录必须用有头浏览器，只是把窗口位置挪到屏幕外（`--window-position=-2400,40`）。

---

## 手机端怎么自动过滑块

安卓没有 Chromium 的鼠标接口，但滑块最终是前端自己判定的，所以整段逻辑可以塞进网页里用 JS 跑：

1. `#slider-img1` / `#slider-img2` 是 data URL，画到 canvas 上用 `getImageData` 取像素，
   把电脑版的梯度匹配算法原样搬过去算缺口；
2. 往 `#sliderDiv .slider` 上派发 `mousedown`，再往 `document` 上派发 `mousemove` / `mouseup`，
   坐标按 easeOutCubic 走，带垂直抖动。插件同时绑了 mouse 和 touch 两套事件（
   `this.slider.addEventListener("mousedown", j)` 与 `"touchstart"`），派发 mouse 事件就够了；
3. 判定通过后插件自己调用 `onSuccess` 提交表单，随后 `POST /authserver/login` 返回 302。

同一份脚本同时给安卓 WebView 和调试用的桌面浏览器使用，实测手机 UA 下 6–7 秒完成
"切标签 → 填账号密码 → 过滑块 → 登录成功"，`verifySliderCaptcha.htl` 返回 200，
登录 POST 返回 302，服务页不再跳回认证页。
