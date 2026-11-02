# SpotTrader 现货交易

基于 Kotlin + Jetpack Compose 的 Android 多交易所**现货**交易客户端。

## 功能
- 统一下单：限价 / 市价 / Maker(只挂单) / 止损 / 追单(Chase)，OCO 止盈止损
- **WebSocket 实时行情**（K线、成交、盘口、Ticker），断线指数退避重连、心跳保活，失败自动回退 REST 轮询
- 专业 K 线图（KLineChart），MACD / RSI 等指标，横屏全屏图表，画线 TP/SL
- 持仓、风险计算器（按风险金额计算仓位）
- USDC 交易对支持
- API 密钥本地加密存储（Android Keystore + EncryptedSharedPreferences），支持 Binance RSA 密钥

## 支持的交易所
Binance · OKX · MEXC（protobuf WS）· Gate.io · Bitget · Bybit · Aster · Hyperliquid

## 构建
需要 JDK 17 与 Android SDK（`local.properties` 设置 `sdk.dir`，或设置 `ANDROID_HOME`）：
```bash
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest          # 单元测试（LIVE_WS=1 时额外连接真实 WebSocket）
```

## 安全说明
- 本仓库**不包含任何 API 密钥**。密钥由用户在 App 内输入，仅加密保存在设备本地，不会发送到除交易所之外的任何服务器。
- 建议 API Key 只开启「现货交易」权限，**关闭提现权限**，并绑定 IP 白名单。
- Hyperliquid 请使用 API 钱包（agent）私钥，不要使用主钱包私钥。
- Release 中的 APK 为 **debug 签名**构建，仅供测试；建议自行从源码构建。

## 免责声明
加密货币交易风险极高，可能损失全部本金。本软件按「现状」提供，**不构成任何投资建议**，作者不对任何交易损失负责。使用前请自行审查代码。

## 许可证
MIT（见 [LICENSE](LICENSE)）。第三方：KLineChart（Apache-2.0）、Lightweight Charts（Apache-2.0）、Jesse（MIT），详见 [NOTICE](NOTICE) 与 `app/src/main/assets/kline/`。

---

## English
Android (Kotlin/Compose) spot trading client for Binance, OKX, MEXC, Gate.io, Bitget, Bybit, Aster and Hyperliquid: real-time WebSocket market data (with reconnect/backoff and REST fallback), KLineChart charts, maker/stop/chase/OCO orders, risk-based position sizing.

- Build: `./gradlew assembleDebug`
- Security: no keys in this repo; keys are entered in-app and stored encrypted on-device (Android Keystore). Disable withdrawal permission on your API keys.
- Disclaimer: crypto trading is high risk. **Not financial advice.** Provided "as is", without warranty.
- License: MIT. Third-party notices in [NOTICE](NOTICE).
