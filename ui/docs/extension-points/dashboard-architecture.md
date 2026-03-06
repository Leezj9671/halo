# 仪表盘（Dashboard）架构说明

本文档说明仪表盘模块的运行原理、使用的组件以及数据流转方式。

---

## 概述

仪表盘是控制台的首页，采用**可拖拽、可缩放的响应式网格布局**，支持多种预设小部件（Widget），并允许插件通过扩展点注册自定义小部件。每位用户的布局配置独立存储，互不影响。

---

## 目录结构

```
console-src/modules/dashboard/
├── Dashboard.vue                  # 仪表盘查看页
├── DashboardDesigner.vue          # 仪表盘编辑/设计页
├── module.ts                      # 路由注册与全局组件注册
├── styles/
│   └── dashboard.css              # vue-grid-layout 样式覆盖
├── components/
│   ├── WidgetCard.vue             # 小部件通用卡片容器
│   ├── WidgetViewItem.vue         # 查看模式下的单个小部件渲染
│   ├── WidgetEditableItem.vue     # 编辑模式下的单个小部件渲染（含删除/配置操作）
│   ├── WidgetHubModal.vue         # 小部件选择弹窗
│   ├── WidgetConfigFormModal.vue  # 小部件配置表单弹窗
│   └── ActionButton.vue           # 编辑模式下的操作按钮（删除、配置）
├── composables/
│   ├── use-dashboard-extension-point.ts  # 插件扩展点加载
│   └── use-dashboard-widgets-fetch.ts    # 布局数据的读取与保存
└── widgets/
    ├── index.ts                   # 内置小部件注册
    ├── defaults.ts                # 默认布局配置
    └── presets/                   # 各内置小部件实现
```

---

## 核心原理

### 1. 网格布局

仪表盘使用 [`vue-grid-layout`](https://github.com/jbaysolutions/vue-grid-layout) 实现可拖拽、可缩放的网格布局。

- **`<grid-layout>`**：整体网格容器，负责管理所有子项的位置与尺寸。
- **`<grid-item>`**：每个小部件在网格中的占位容器。

关键配置参数：

| 参数 | 含义 |
|------|------|
| `col-num="12"` | 网格总列数为 12 |
| `row-height="30"` | 每行高度 30px，小部件高度 = `h * 30` px |
| `margin="[10, 10]"` | 小部件之间的水平/垂直间距为 10px |
| `responsive` | 启用响应式，切换断点时自动切换布局 |
| `breakpoints` | 断点定义：`{ lg: 1200, md: 996, sm: 768, xs: 480 }` |
| `cols` | 各断点列数：`{ lg: 12, md: 12, sm: 6, xs: 4 }` |

查看模式（`Dashboard.vue`）中 `is-draggable` 和 `is-resizable` 均为 `false`；  
编辑模式（`DashboardDesigner.vue`）中两者均为 `true`。

---

### 2. 小部件定义（DashboardWidgetDefinition）

每个小部件通过 `DashboardWidgetDefinition` 描述自身，定义在 `@halo-dev/ui-shared` 包中：

```ts
interface DashboardWidgetDefinition {
  id: string;                       // 唯一标识，如 "core:post:stats"
  component: Raw<Component>;        // Vue 组件（需用 markRaw 包裹）
  group: string;                    // 分组（用于小部件选择弹窗的分类）
  configFormKitSchema?: ...;        // 配置表单 FormKit Schema（可选）
  defaultConfig?: Record<string, unknown>; // 默认配置
  defaultSize: { w, h, minW?, minH?, maxW?, maxH? }; // 默认尺寸与约束
  permissions?: string[];           // 访问权限（可选）
}
```

所有内置小部件在 `widgets/index.ts` 中注册，外部插件的小部件通过扩展点动态加载。

---

### 3. 小部件实例（DashboardWidget）

用户的实际布局由 `DashboardWidget[]` 数组描述，每个元素是一个小部件实例：

```ts
interface DashboardWidget {
  i: string;        // 实例唯一 ID（uuid），用于 vue-grid-layout 的 key
  x: number;        // 网格列坐标（从 0 开始）
  y: number;        // 网格行坐标（从 0 开始）
  w: number;        // 宽度（网格列数）
  h: number;        // 高度（网格行数）
  minW?, minH?      // 最小尺寸约束
  id: string;       // 对应的 DashboardWidgetDefinition.id
  config?: Record<string, unknown>; // 此实例的配置（覆盖默认配置）
  permissions?: string[];           // 权限控制
}
```

---

### 4. 响应式布局（DashboardResponsiveLayout）

用户的完整布局按断点分别存储：

```ts
interface DashboardResponsiveLayout {
  lg?: DashboardWidget[];   // 桌面（≥1200px）
  md?: DashboardWidget[];   // 平板横屏（≥996px）
  sm?: DashboardWidget[];   // 平板（≥768px）
  xs?: DashboardWidget[];   // 手机（≥480px）
  xxs?: DashboardWidget[];  // xs 的镜像（vue-grid-layout 内部要求）
}
```

如果某个断点没有数据，会回退到更大断点的布局。若用户从未设置过，则使用 `widgets/defaults.ts` 中的默认布局。

---

### 5. 数据存储与读取

布局配置通过 **用户偏好设置 API** 持久化，每位用户独立存储：

| 操作 | API | 路径 |
|------|-----|------|
| 读取布局 | `ucApiClient.user.preference.getMyPreference` | `GET /apis/uc.api.halo.run/v1alpha1/user-preferences/dashboard-widgets` |
| 保存布局 | `ucApiClient.user.preference.updateMyPreference` | `PUT /apis/uc.api.halo.run/v1alpha1/user-preferences/dashboard-widgets` |

读写操作封装在 `composables/use-dashboard-widgets-fetch.ts` 中，使用 `@tanstack/vue-query` 管理请求缓存：

- **`useDashboardWidgetsFetch`**：编辑模式使用，返回可写的 `layout` 和 `layouts` ref。
- **`useDashboardWidgetsViewFetch`**：查看模式使用，只读。

---

### 6. 小部件数据来源

各内置小部件使用以下数据源：

| 小部件 | 数据来源 | API |
|--------|----------|-----|
| 文章统计（PostStatsWidget） | 全局统计数据 | `GET /apis/api.console.halo.run/v1alpha1/stats` |
| 独立页面统计（SinglePageStatsWidget） | 全局统计数据 | 同上 |
| 评论统计（CommentStatsWidget） | 全局统计数据 | 同上 |
| 用户统计（UserStatsWidget） | 全局统计数据 | 同上 |
| 访问量统计（ViewsStatsWidget） | 全局统计数据 | 同上 |
| 点赞统计（UpvotesStatsWidget） | 全局统计数据 | 同上 |
| 热门文章（TrendingPostsWidget） | 文章列表（按访问量排序） | `GET /apis/api.console.halo.run/v1alpha1/posts?sort=stats.visit,desc` |
| 最近发布（RecentPublishedWidget） | 文章列表 | `GET /apis/api.console.halo.run/v1alpha1/posts` |
| 待审评论（PendingCommentsWidget） | 评论列表 | `GET /apis/api.console.halo.run/v1alpha1/comments` |
| 通知（NotificationWidget） | 通知列表 | UC API |

全局统计数据通过 `console-src/composables/use-dashboard-stats.ts` 中的 `useDashboardStats()` composable 统一请求，内部使用 `@tanstack/vue-query` 缓存，避免多个小部件重复请求。

> **说明：全局统计 API 只返回累计总量，没有按天的细分数据。**  
> 如需展示按天的访问趋势图，请使用下方描述的每日统计 API。

---

### 6a. 后端每日统计数据（DailySiteStats）

> **背景**：`Counter` 扩展模型只存储**累计总量**（`visit`、`upvote`、`totalComment` 等），不包含任何时间维度信息，因此无法直接用于绘制折线图或柱状图。为此，后端新增了 `DailySiteStats` 扩展模型，专门记录**每天**的站点访问数据。

#### 数据存储原理

- **模型**：`DailySiteStats`（`metrics.halo.run/v1alpha1`）
- **主键（`metadata.name`）**：UTC 日期字符串，格式 `yyyy-MM-dd`，例如 `2024-01-15`
- **字段**：`visit`（当日访问数）、`upvote`（当日点赞数）、`comment`（当日新增评论数）

每当访客触发 `VisitedEvent` 时，`VisitedEventReconciler` 除了更新对应内容的 `Counter`（累计），还会同时将当日访问计数累加到内存中的 `pooledDailyVisitsMap`。调度器每分钟把内存数据批量写入数据库对应的 `DailySiteStats` 记录（不存在则自动创建），保证了**高吞吐下的低写放大**。

```
访客访问页面
  │
  ▼ VisitedEvent 被触发
  │
  ├─ pooledVisitsMap[counterName] += 1       (per-resource 累计，已有逻辑)
  └─ pooledDailyVisitsMap[today] += 1        (新增：当日站点总访问，内存缓冲)
          │
          ▼ 每分钟 @Scheduled 刷新
  ┌─ Counter (per-resource 累计写入 DB)
  └─ DailySiteStats[yyyy-MM-dd] (当日汇总写入 DB，不存在则 create)
```

#### 每日统计 API

```
GET /apis/api.console.halo.run/v1alpha1/stats/daily?days=30
```

- `days`（可选，默认 30，最大 365）：返回最近 N 天的数据
- 返回结果为 JSON 数组，**包含完整的日期序列（没有数据的天补 0）**，便于前端直接用于图表：

```json
[
  { "date": "2024-01-01", "visit": 0,   "upvote": 0, "comment": 0 },
  { "date": "2024-01-02", "visit": 128, "upvote": 3, "comment": 7 },
  ...
  { "date": "2024-01-30", "visit": 256, "upvote": 8, "comment": 12 }
]
```

此 API 可直接用于在仪表盘中实现访问趋势折线图、柱状图等时序可视化小部件。

---

### 7. 插件扩展点

外部插件可通过 `console:dashboard:widgets:create` 扩展点注册自定义小部件，由 `composables/use-dashboard-extension-point.ts` 在组件挂载时遍历所有已加载插件的 `extensionPoints` 并收集定义。

插件注册的小部件 ID 会自动添加插件名前缀，避免与内置小部件冲突：  
`definition.id = \`${pluginName}-${definition.id}\``

---

### 8. 小部件渲染流程

```
Dashboard.vue / DashboardDesigner.vue
  │
  ├─ 合并内置定义 + 插件定义 → availableWidgetDefinitions（通过 provide/inject 下发）
  │
  └─ grid-layout（布局容器）
       └─ WidgetViewItem / WidgetEditableItem（每个小部件实例）
            │  根据 item.id 从 availableWidgetDefinitions 中查找对应的组件
            └─ <component :is="definition.component" :config="item.config" />
```

权限检查在 `WidgetViewItem` / `WidgetEditableItem` 内通过 `utils.permission.has(item.permissions)` 进行，无权限的小部件不会渲染。

---

### 9. 编辑模式与配置

编辑模式（`DashboardDesigner.vue`）在查看模式基础上额外提供：

- **拖拽 / 缩放**：通过 `vue-grid-layout` 的 `is-draggable` / `is-resizable` 启用。
- **添加小部件**：通过 `WidgetHubModal` 选择小部件定义，调用 `handleAddWidget` 生成新实例插入布局。
- **删除小部件**：`WidgetEditableItem` 上的删除按钮触发 `handleRemove`。
- **配置小部件**：`WidgetEditableItem` 检测到 `configFormKitSchema` 存在时显示配置按钮，打开 `WidgetConfigFormModal`，基于 `FormKit` 渲染配置表单，保存后更新 `item.config`。
- **响应式预览**：通过 `VTabbar` 切换断点，模拟不同屏幕宽度下的布局，配合 `designContainerStyles` 动态限制容器宽度。
- **保存**：调用用户偏好 API 将 `layouts` 持久化，并刷新两个 vue-query 缓存键（`core:dashboard:widgets` 和 `core:dashboard:widgets:view`）。

---

## 关键依赖

| 依赖 | 用途 |
|------|------|
| `vue-grid-layout` | 可拖拽可缩放的响应式网格布局 |
| `@tanstack/vue-query` | API 请求缓存与状态管理（`useQuery`） |
| `@halo-dev/api-client` | 后端 API 调用（`consoleApiClient`、`ucApiClient`） |
| `@halo-dev/components` | 基础 UI 组件（`VButton`、`VEmpty`、`VLoading`、`VModal` 等） |
| `@halo-dev/ui-shared` | 共享类型定义与工具函数（`DashboardWidgetDefinition`、`utils` 等） |
| `@number-flow/vue` | 数字动画组件（统计小部件中的数字增减动效） |
| `overlayscrollbars-vue` | 列表类小部件的自定义滚动条 |
| `es-toolkit` | `cloneDeep`、`isEqual` 等工具函数 |
| `FormKit` | 小部件配置表单的渲染引擎 |

---

## 数据流图

```
用户访问 Dashboard
       │
       ▼
useDashboardWidgetsViewFetch()
  ├─ 调用 GET /user-preferences/dashboard-widgets
  └─ 返回 DashboardResponsiveLayout（或使用 defaults.ts 中的默认布局）
       │
       ▼
grid-layout 渲染当前断点下的 DashboardWidget[]
       │
       ▼
WidgetViewItem 遍历每个实例
  ├─ 从 availableWidgetDefinitions 中找到对应的 Vue 组件
  ├─ 权限检查（utils.permission.has）
  └─ <component :is="widgetComponent" :config="item.config" />
       │
       ▼
各小部件组件内部通过 useQuery 独立请求所需数据
（统计类共用 useDashboardStats，列表类各自请求）
```

---

## 如何新增内置小部件

1. 在 `widgets/presets/` 下新建 Vue 组件，接收 `config` prop。
2. 在 `widgets/index.ts` 中导入并注册 `DashboardWidgetDefinition`。
3. （可选）在 `widgets/defaults.ts` 中将其加入默认布局。
4. 在所有语言的 `src/locales/*.json` 中添加对应的 i18n 翻译键。

> 如需通过插件扩展，请参阅 [仪表盘扩展点文档](./dashboard.md)。
