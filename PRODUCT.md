# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

前端：Vue 3 + Vite（用户选定，独立前端工程，Vue Router + Pinia，可长成与后端对接的完整前端）。
后端：Spring Boot 3.1.5 + Java 17 + MyBatis 3.0.2 + MySQL 8，单体服务，本地 `localhost:8080`；前端开发端口 `5173`，后端 `WebMvcConfig` 已开 `allowedOriginPatterns("*")` 跨域。
前端直连上述真实接口，不使用 mock 数据（用户选定的数据来源）。

## Users

- **首要用户：想出门但没时间做攻略的自由行旅行者。** 场景是出发前一两周、手机或笔记本前，心里只有一个模糊念头（"想去重庆，三四天，别太累，爱吃"），没有具体景点清单。他的工作是把模糊念头变成一份能照着走的日程。
- **次要用户：行程已经生成、需要微调的人。** 在已生成的行程树上改天数、改节点、删一天。
- **隐含受众（对落地页重要）：评审与验收者。** 代码注释明确出现"毕设"语境，落地页同时承担"证明这是一个完整、可信、做完了的系统"的任务。

## Product Purpose

行迹把"我想去某地玩几天"这句话变成一棵可执行的行程树——按天分层、每天挂景点/餐饮/交通/住宿节点、带时间段与预计花费——并且保证树上每个地点都是高德地图里真实存在的点，而不是模型编出来的名字。

成功 = 用户几分钟内拿到一份可信、可改、可分享的行程，且不需要自己去核对"这地方到底存不存在"。

## Positioning

**生成之后还有一道核验。** 同类 AI 行程产品停在"模型吐出 JSON"这一步，行迹多走一段：拿模型给的地点名去高德 POI 检索，命中就回填真实 `place_id`，没命中就把该节点标成 `is_abnormal=1` 异常并告诉用户。这把"AI 会编地名"这个品类最大的失信点变成了产品里的一个可观测字段。

## Operating Context

- 真实工作流：注册/登录（手机号+BCrypt，或 QQ/微信第三方，`oauth.mock` 目前为 true）→ 对话提需求 → AI 生成行程树 → 校验（高德核验+预算超支+同天时间重叠检查）→ 手动编辑天与节点 → 生成分享链接给别人看 → 旅途中上传照片视频进素材库。
- 行程状态机：`0-草稿 / 1-已生成 / 2-已校验`，这个三段式是产品的核心叙事。
- 地图数据来自高德：POI 检索（`/v3/place/text`）与行政区划（`/v3/config/district`），返回坐标由 `GeoUtil` 做 GCJ-02 → WGS84 转换。城市清单约 340 个地级市，服务层缓存 6 小时。
- AI 侧是"多模型 + 任务路由"：`AiTask` 枚举（REQUIREMENT_PARSE / ITINERARY_GENERATE / CHAT / INSPIRATION / SPOT_RECOMMEND）在 yml 的 `routing` 里映射到模型别名，行程生成走强推理档，对话与文案走快档。
- 素材回收站保留 30 天后由定时任务物理清理。

## Capabilities and Constraints

**已实现且可对外陈述：**
- 手机号注册登录、JWT（HS256，有效期 1440 分钟）、QQ/微信第三方登录绑定
- AI 多轮对话（会话 + 消息持久化）
- AI 生成行程树（行程 → 天 → 节点三层一次落库）
- 行程三层编辑：改主体、加/改/删天、加/改/删节点
- 校验：高德 POI 核验回填 `place_id` + 预算超支 + 同天时间重叠
- 分享链接（UUID 去横线 token，可撤销）
- 素材上传（图片 ≤10MB / 视频 ≤50MB 白名单）、软删回收站、定时清理
- 免登录对外接口：`/api/place/cities`、`/api/place/recommend`、`/api/place/recommend/smart`、`/api/place/inspiration`

**约束与未决事实：**
- `itinerary.status` 只有 0/1/2 三态，没有"已发布/已出行"。
- `route_segment` 表与 `RouteSegment` 实体已建但**无 mapper 与服务**，相邻节点真实路线（REQ-012）未实现 —— 落地页不得宣称有路线规划/导航能力。
- `ai_service_config` 表已建但未接线，多模型目前由 yml 配置，不是运行时可配。
- `travel_media.itinerary_id` / `place_id` 上传时始终为空，素材与行程尚未真正关联。
- Redis 依赖存在但自动配置被排除，代码中无实际使用。
- 第三方登录当前 `oauth.mock=true`，真实 QQ/微信授权分支是 TODO。
- **尚未决定：** 是否公开定价、是否有真实用户量与案例（无任何证据，一律不得编造）。

## Brand Commitments

- 名称：**行迹**（拼音 xingji）。后端 `spring.application.name` 与 `pom.xml` 的 `<description>` 均为"行迹 - 智能旅游行程规划与管理系统"。
- 声音：中文简体，平实、不吹嘘。既有系统文案就是这种口吻（"未登录或登录已失效"、"地点未在高德找到,已标异常"、"分享链接无效或已撤销"）。
- 不得把产品说成已商用、已有用户量、已有合作伙伴。它是一个完成度很高的个人项目/毕设系统。

## Evidence on Hand

- 真实高德 Web 服务 key 已配置在 `src/main/resources/application.yml`，行政区划与 POI 检索是真实可调用的数据源。
- 真实大模型凭据已配置：快档 智谱 GLM-4-Flash（`open.bigmodel.cn`），强档 SiliconFlow `deepseek-ai/DeepSeek-R1-0528-Qwen3-8B`。
- 完整数据库 schema：`src/main/resources/sql/schema.sql`，12 张表。
- 四份真实 system 提示词：`src/main/resources/prompts/`（行程生成、对话、种草文案、景点推荐）。
- 素材上传目录 `D:/y/develop/xingji-uploads`，通过 `/uploads/**` 对外提供静态访问。
- **缺失、后续工作不得伪造：** 真实截图、真实用户评价、真实行程案例数据、团队与联系方式、任何性能或规模数字。

## Product Principles

1. **可信优先于惊艳。** 每个 AI 产出都过一道真实世界核验，异常要显式暴露给用户，不藏着。
2. **行程是一棵树，不是一段文字。** 天与节点是可编辑的结构，用户始终拥有最终决定权。
3. **地图数据是地基。** 城市与景点来自高德真实 POI，产品不生产地理事实，只组织它。
4. **模型是可替换的零件。** 业务只声明任务，路由在配置里；换模型不动业务代码。
5. **说得出边界。** 没实现的（路线规划）就不出现在承诺里。

## Accessibility & Inclusion

未确立产品级无障碍标准；按 Web 基本合格线执行（语义标签、键盘可达、可见焦点、图片替代文本、中文正文对比度达标）。
