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

## 包结构

`src/main/java/com/rates252/`：`DayCount`、`Validate`、`CurveConfig`、`DepositQuote`、
`SwapQuote`、`DiscountCurve`、`CurveBootstrapper`、`BootstrapResult`、`InstrumentRepricing`、
`BootstrapException`、`Bond`、`BondCashflow`、`BondPrice`、`BondPricer`、`QuoteDv01`、
`Dv01Report`、`RiskEngine`、`Main`。
自测：`src/test/java/com/rates252/Rates252Test.java`。
