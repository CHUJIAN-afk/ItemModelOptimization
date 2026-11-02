# 物品模型渲染优化

Minecraft 1.20.1 / Forge 47.4.22 及以上的客户端模组，Java 17，MIT 许可证。

通过 GPU 实例化合并普通掉落物的重复模型绘制，保留原版自旋、浮动和地面阴影。世界中使用标准 GROUND 显示方式提交的物品也可复用模型缓存；附魔光效、动态模型、自定义渲染器等特殊情况继续使用原版渲染。

## 配置

配置文件为 `config/item_model_optimization-client.toml`，模组列表的“配置”按钮提供独立设置页。

```toml
enabled = true
computeAnimation = true
skipVisibilityChecks = true
```

- `enabled`：优化总开关。
- `computeAnimation`：在 GPU 上预计算物品自旋和浮动，需要 OpenGL 4.3；旧设备使用顶点动画。
- `skipVisibilityChecks`：默认跳过遮挡与视锥检查，对渲染距离内、本模组接管的已加载普通掉落物全量提交；关闭后恢复原版及 Embeddium 的检查。

`/imo enabled false` 停用优化，`/imo stats` 查看当前渲染统计。

## 性能汇总

各分支最终测试报告统一保存在 `master`（1.21.1 NeoForge）分支的 `BENCHMARK.md`，本分支不保留独立报告或图表。

## 构建

使用 Java 17，执行 `gradlew.bat build`。仅生成稳定版 `build/libs/item_model_optimization-1.20.1.2.jar`，无需额外安装配置界面库。
