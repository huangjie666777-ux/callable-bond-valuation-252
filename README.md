# rates252

固定收益估值程序的利率曲线与债券定价库（Java 17 + Apache Commons Math 3.6.1，无前端、无 HTTP）。

## 构建与运行

```
make compile   # 编译到 target/classes
make test      # 运行 JUnit Jupiter 自测（src/test/java）
make run       # 运行估值与风险示例 com.rates252.Main
make clean
```

依赖位于 `third_party/`（见 `dependencies.lock.json`）：Commons Math 3.6.1 与 JUnit Platform
Console Standalone 1.10.3。要求 JDK 17 与 GNU Make。

## 约定

- 所有日期为合同日期，计息方式统一 ACT/365F：`alpha = 实际天数 / 365`。
- 报价带唯一 ID；拒绝 NaN/无穷、非法日期、非正分母等输入。
- 折现曲线在估值日 `D(valueDate) = 1`，所有 `D` 必须为正、有限。
- 时间轴为估值日起的 ACT/365F 年数；相邻节点间对 `log D` 线性插值。
- 曲线区间（含两端）之外的查询一律拒绝。允许负报价与 `D > 1`。

## 曲线引导

输入估值日、存款报价与固定对浮动互换报价（`CurveBootstrapper`）：

- 存款从估值日起息到期：`D = 1 / (1 + r * alpha)`；分母非正有限则失败。
- 互换从估值日起息，付息日严格递增，末年为终点：
  `S * sum(alpha_i * D_i) = 1 - D_T`，`alpha_i` 按相邻付息日（首期为估值日）ACT/365F 计算。
- 按终点升序逐支求解，重复终点拒绝；新段内的付款日以待求终点节点做 log D 线性插值。
- 互换用 Commons Math `BrentSolver` 在 `log D_T` 上求根（根对应严格正的 `D_T`），自动扩展括号。
- `CurveConfig(absoluteTolerance, maxEvaluations, reproduceTolerance)` 可配求根预算与复现容差。
- 每支求解后都重新定价；未达复现容差则整条曲线失败（`BootstrapException`），并返回
  截至该支（含失败支）的逐支复现值与残差。成功结果 `BootstrapResult` 返回曲线与全部复现记录。

## 债券定价

`Bond` 接收发行日、严格递增的付息日（最后一日为到期日）、正面额与非负年票息：

- 计息日与支付日相同，无除息期；每期息票 = 面额 × 票息 × 相邻付息日 ACT/365F，末期返还本金。
- 结算日必须在 `[发行日, 到期日)` 且落在曲线范围内（含端点）。
- 仅保留支付日严格晚于结算日的现金流，以 `D(支付日) / D(结算日)` 折现。
- `BondPricer.price` 返回每百元全价（dirty）、应计利息与净价（clean = dirty - 应计）；
  付息日应计为零。

## DV01 风险

`RiskEngine` 对每条报价分别上、下移 1 bp（默认 0.0001，可配），每次都重新引导整条曲线后
重新定价债券，净价 DV01 = `(P(下移) - P(上移)) / 2`。某个方向引导或定价失败时，该报价结果
`QuoteDv01` 只带失败原因，不写零。

## 发行人提前赎回债券估值

`CallableBondPricer.price(bond, settlementDate, curve, sigma, stepDays, callSchedule)`
在重组二叉短率树上做向后递推，量化发行人赎回权成本：

- 网格：结算日至到期按等步长天数划分，跨度必须整除步长且步数不超过 300；
  结算后全部付息日必须落在网格上；整个网格不得超出曲线范围。
- 树（`ShortRateTree`）：`dt = 步长天数 / 365`，i 层 j 节点短率
  `r = a_i + (2j - i) * sigma * sqrt(dt)`，上下概率各半，连续复利 `exp(-r * dt)` 折现；
  允许负利率与 `sigma = 0`（`sigma` 须有限非负）。
- 校准：逐层二分求解 `a_i`，使累计状态价格复现 `D(下一网格日) / D(结算日)`；
  每层残差随结果返回，残差非有限或超容差（1e-10）即抛异常，不交付价格。
- 赎回表（`CallDate`）：每百元赎回价须正且有限；赎回日只能是结算后、到期前的
  付息日，重复或非法日期一律拒绝。
- 行权：赎回日先付当期票息，发行人再比较继续价值与不含当期票息的赎回价，取较小者，
  等值时继续；赎回后不再支付未来息本；无赎回时到期付息还本。
- 输出（`CallableBondPrice`）：每百元可赎回全价、应计、净价、无赎回对照全价
  （沿原 `BondPricer` 现金流链）及两者价差（赎回权成本）；`CallExerciseInfo`
  给出每个赎回日各节点的短率、继续价值与行权标记（非静态收益率阈值）。

`make run` 示例末尾展示同一 5% 债券有无赎回的估值对照与赎回日行权汇总。

## 包结构

`src/main/java/com/rates252/`：`DayCount`、`Validate`、`CurveConfig`、`DepositQuote`、
`SwapQuote`、`DiscountCurve`、`CurveBootstrapper`、`BootstrapResult`、`InstrumentRepricing`、
`BootstrapException`、`Bond`、`BondCashflow`、`BondPrice`、`BondPricer`、`QuoteDv01`、
`Dv01Report`、`RiskEngine`、`CallDate`、`ShortRateTree`、`CallExerciseInfo`、
`CallableBondPrice`、`CallableBondPricer`、`Main`。
自测：`src/test/java/com/rates252/Rates252Test.java`。
