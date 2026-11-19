# 行迹 · 前端（xingji-app）

全新的 Vue 3 + Vite 前端工程，位于同一仓库的 `frontend/` 目录，当前已实现**营销首页**。
后端是 Spring Boot（`localhost:8080`），本前端开发端口 `5174`（刻意避开旧的 `xingji-web:5173`）。

## 快速开始

```bash
# 1) 确保后端在跑（Spring Boot，8080）
# 2) 安装依赖
npm install
# 3) 起开发服务器
npm run dev      # → http://127.0.0.1:5174
# 生产构建 / 预览
npm run build
npm run preview
```

`vite.config.js` 已把 `/api` 与 `/uploads` 代理到 `http://localhost:8080`，
所以业务代码里只写相对路径，部署时换成 Nginx 反代即可，不用改一行业务代码。

## 设计基调

- 配色：白（纸）为主、黑（墨）为字、**晒蓝**为唯一主张色；**红只在「地点没核验上」时出现**，别处不用。
- 字体：中文标题用系统宋体（衬线）、正文用苹方/黑体；西文标题 Fraunces、编号/坐标用 IBM Plex Mono。中文走系统字体，离线也能落版。
- 动效：全站一条签名缓动 `cubic-bezier(.22,1,.36,1)`。每个元素都有出现 / 过渡 / 消失；
  招牌动效是「生成 → 核验」的形变（Vue 原生 `<TransitionGroup>` 的 FLIP，即 Morph 平滑衔接）。
- 图标：全部用开源图标库 `lucide-vue-next`，不用 emoji。
- 无障碍：语义标签、键盘可达、可见焦点、尊重 `prefers-reduced-motion`。

## 目录结构

```
src/
  main.js                 应用入口，注册 v-reveal 指令 / router / pinia，bootstrap 会话
  App.vue                 外壳（RouterView + 全站 AuthModal 浮层）
  router/index.js         路由：'/' 首页、'/me' 个人中心、'/explore' 探索、'/trips' 我的行程、'/s/:token' 公开分享
  api/xingji.js           后端接口层，统一解包 Result<T>={code,msg,data}
  stores/auth.js          会话 store（token/user、登录注册、401 处理）
  stores/ui.js            界面 store（登录/注册弹窗的开关与回跳）
  stores/explore.js       探索与行程 store（对话、画像、节点编辑、路线、分享）
  stores/trips.js         行程列表 store
  composables/useLenis.js 平滑滚动引擎（只在首页挂载）
  composables/useScroll.js  全站单 rAF 滚动调度器 Scroll + useScrollFrame
  composables/useReveal.js  滚动揭示指令 v-reveal（IntersectionObserver）
  styles/theme.css        设计令牌（Tier1 画板 → Tier2 语义）+ 日夜与浅色主题覆写
  styles/tokens.css       兼容层令牌
  styles/base.css         版式底座 + 揭示/微交互工具类
  views/LandingImmersive.vue 首页（天空/大地滚动叙事，组合 components/nz/*）
  views/ExploreView.vue   探索页（满幅地图 + 会话/行程浮层）
  views/TripsView.vue     我的行程（左列表 + 中会话 + 右每日规划）
  views/ProfileView.vue   个人中心（资料 + 相册/回收站 + 头像取景）
  views/ShareView.vue     公开分享页（免登录，凭 token 只读）
  components/
    AppNav.vue            功能页顶栏 + 头像下拉
    AuthModal.vue         ★ 登录/注册弹窗（Teleport+Transition，Morph 切换，全站复用）
    FieldInput.vue        下划线输入（标签浮起、焦点抽线、错误转红、密码显隐）
    ConfirmModal.vue      统一二次确认弹窗
    PasswordModal.vue     修改密码
    ProfileEditModal.vue  编辑资料（昵称/性别/邮箱/头像）
    AvatarCropper.vue     头像取景：整图铺底 + 圆形取景框 + 缩放，裁出来上传
    ChatSidebar.vue       AI 会话（画像卡、锚点、景点卡片）
    ItineraryPanel.vue    探索页行程面板（节点编辑、路线、校验、分享）
    TripArrangement.vue   我的行程「每日规划」分组
    DayEditForm.vue / NodeEditForm.vue  天与节点的编辑小表单
    MapPanel.vue          高德地图（城市热点、搜索、路线）
    nz/                   首页区块：NavBar / SkyHero / StoryIntro / FeaturedWork / TestimonialsDeck / GardenFooter
```

## 与后端的对应

首页与分享页只碰后端**免登录**接口（`WebMvcConfig` 的 `excludePathPatterns` 名单）：
`/api/place/recommend`、`/api/place/recommend/smart`、`/api/place/inspiration`、`/api/public/share/{token}`。
其余页面（探索、我的行程、个人中心）都要带 JWT：对话与行程生成/编辑、地点校验、路线计算、素材与分享签发。

> 文案与承诺严格对齐 `PRODUCT.md`：不虚构用户量与评价、第三方登录是演示态——这些「边界」直接写进了首页。

## 已完成：用户模块（认证 + 个人中心）

登录/注册**不再各占一个路由**，而是首页（及全站）上的一个浮层 `AuthModal.vue`：
顶栏「登录 / 开始使用」按钮直接唤起，登录与注册在同一弹窗里用 Morph 分段切换。
`/me`（`ProfileView`）仍是独立页面，需登录。开关状态由 `stores/ui.js` 统一管理。
关键文件：`stores/auth.js`（会话）、`api/xingji.js`（register/login/oauth/getProfile/updateProfile）、
`components/FieldInput.vue`（下划线输入：标签浮起、焦点抽线、错误转红、密码显隐）、
`components/AppNav.vue`（登录区顶栏 + 头像下拉）。

鉴权机制：token 存 `localStorage.xingji.token`，需登录接口自动带 `Authorization: Bearer`；
后端 401 时全局回调清态并就地唤起登录弹窗（若正停在受保护页，会记住回跳目标）；
路由守卫 `requiresAuth` 拦未登录——不跳登录页，而是回首页并弹出登录框。
登录/注册成功后：有回跳目标就 `replace` 过去，否则就地收起弹窗、顶栏切成头像。
注册成功后用同一手机号密码自动登录，省一次输入。第三方 QQ/微信走后端 mock 接口。

> 校验规则与后端 `@Valid` 一致：手机号 `^1[3-9]\d{9}$`、密码 6–32、昵称必填 ≤20、邮箱选填。
> 错误提示优先显示后端返回的中文 `msg`。

**增强（本轮）**：头像上传复用后端 `/api/media/upload`（上传拿到 `filePath` → 拼成 `/uploads/{filePath}` 填进 `avatar` → 仍走「保存修改」落库），前端做了类型/大小校验与上传中态；退出登录加了二次确认弹窗；「修改密码」已全链路打通——后端新增 `PUT /api/user/password`（`ChangePasswordDTO` + `UserService.changePassword`，BCrypt 验原密码、只更新 password），前端 `PasswordModal.vue` 三字段弹窗（原/新/确认，前端做一致性与强度校验）。
> 顺带修正：后端 `application.yml` 的 `driver-class-name` 由已废弃的 `com.mysql.jdbc.Driver` 改为 `com.mysql.cj.jdbc.Driver`（Connector/J 8.x 必需，否则启动即 `ClassNotFoundException`）。

## 下一步（前端后续模块）

AI 对话（`/api/chat`）、行程列表与详情三层编辑（`/api/itinerary`）、
校验结果高亮异常（`ValidationResultVO`）、分享页（`/api/public/share/{token}`）、素材库（`/api/media`）。
在 `router/index.js` 里挂新路由即可，工程结构无需回改。
