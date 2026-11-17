/**
 * ECharts 按需引入入口（全站统一从这里 import）
 * 只注册本项目实际使用的图表与组件，替代 `import * as echarts from 'echarts'` 全量引入，
 * 可显著减小打包体积（未使用的 bar/map/geo/3D 等模块不会进包）。
 * 新增图表类型时记得在下方补注册对应模块，否则渲染时报组件未注册。
 */
import * as echarts from 'echarts/core'
import { LineChart, ScatterChart, GraphChart, TreeChart } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  GridComponent,
  LegendComponent,
  DataZoomInsideComponent,
  DataZoomSliderComponent,
  ToolboxComponent,
  GraphicComponent,
  MarkLineComponent,
  MarkPointComponent,
  MarkAreaComponent,
  AxisPointerComponent,
  DatasetComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  LineChart, ScatterChart, GraphChart, TreeChart,
  TitleComponent, TooltipComponent, GridComponent, LegendComponent,
  DataZoomInsideComponent, DataZoomSliderComponent, ToolboxComponent,
  GraphicComponent, MarkLineComponent, MarkPointComponent, MarkAreaComponent,
  AxisPointerComponent, DatasetComponent,
  CanvasRenderer
])

// 注册内置 dark 主题（Dashboard 使用 echarts.init(el, 'dark')）
// 该文件仅依赖 echarts 核心（registerTheme），不会把全量图表模块拉进包
import 'echarts/theme/dark'

export default echarts
