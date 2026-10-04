# Oritech 裂变反应堆规划器

一个单文件、离线可用的网页工具，用于设计 Oritech 裂变反应堆布局，并在不实际建造的情况下判断它是否稳定。

用任意浏览器打开 `index.html` 即可。无需服务器、无需构建、无依赖、不联网。

同时由 `pages` 工作流（`.github/workflows/pages.yml`）发布为 GitHub Pages 站点，直接把本目录作为站点根目录。

## 功能

- 用燃料棒、反射器、热量管道、散热口、吸收器和热回收口绘制**单层内部布局**。
- 模拟复刻 `ReactorControllerBlockEntity.serverTick`，包括完全一致的整数除法，
  以及每刻固定顺序：燃料棒 &rarr; 热量管道 &rarr; 吸收器 &rarr; 散热口 &rarr; 回收口。
- 实时指标：RF/刻、产热/散热、净热量、最热部件温度（K）、折算单层温度、5 秒热量趋势，以及预计熔毁时间。
- 可一直运行到稳态，也可逐刻单步；带热量曲线和警告线/熔毁线参考。
- 参数面板对应 `OritechConfig`（`heatHeightSlope`、`maxHeat`、`absorberRate`、`recoveryRate`、`rfPerHeat` 等），
  可以直接预览某个整合包改动后的数值。
- 校验提示，例如"完全没有散热"或"仅靠回收口撑不住这个设计"。

## 准确性

- 公式与整数语义与 Java 源码一致，并由 `selftest.mjs` 验证。
- **偏差说明**：游戏遍历的是 `HashMap`，同一刻内组件处理顺序是任意的。
  本工具使用固定顺序，因此每刻结果可能相差几点 K。
  稳态行为与所有单组件计算完全一致；请把本工具当作设计辅助，而非逐位精确复刻。
- 运行时假设燃料与冷却剂无限供应（每个口都是满的），这对应供应充足的反应堆。

## 自检

```
node tools/reactor-planner/selftest.mjs
```

它会从 `index.html` 中提取脚本，在极简 DOM 桩上运行，并执行 41 条断言，
覆盖脉冲规则、能量与热量公式、每种散热组件、堆叠高度缩放、熔毁阈值以及趋势/预计熔毁。
失败时退出码非零；`.github/workflows/pages.yml` 会在部署前先运行它。

## 如何保持同步

数值存在于两处：Java 源码与本规划器。反应堆机制变更时请同步修改两者，并重新运行自检。相关 Java 位置：

- `ReactorControllerBlockEntity.serverTick` —— 每刻顺序、热量、能量、熔毁
- `ReactorRodBlock` —— 各燃料棒类型的 `rodCount` / `internalPulses`
- `OritechConfig` —— `reactor` 段的默认值
