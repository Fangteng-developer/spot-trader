package com.morris.spottrader.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import com.morris.spottrader.ex.Quotes
import com.morris.spottrader.ex.SymPair
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.morris.spottrader.AppVM
import com.morris.spottrader.ex.Exchanges
import com.morris.spottrader.ex.plain
import com.morris.spottrader.sec.Crypto
import com.morris.spottrader.sec.NetInfo
import com.morris.spottrader.sec.StoredKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

fun copy(ctx: Context, label: String, s: String, sensitive: Boolean = false) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, s)
    if (sensitive) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
}

private fun BigDecimal.f(n: Int) = setScale(n, RoundingMode.HALF_UP).toPlainString()

@Composable
fun PositionsScreen(vm: AppVM, initialRiskOpen: Boolean = false) {
    var tab by remember { mutableIntStateOf(0) }; var riskOpen by remember { mutableStateOf(initialRiskOpen) }
    var tpslFor by remember { mutableStateOf<com.morris.spottrader.Position?>(null) }
    tpslFor?.let { TpSlDialog(vm, it) { tpslFor = null } }
    LaunchedEffect(vm.exchangeId) { if (vm.hasKeys()) vm.loadPositions() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val total = vm.positions.mapNotNull { it.value }.fold(BigDecimal.ZERO, BigDecimal::add)
        val pnl = vm.positions.mapNotNull { it.pnl }.fold(BigDecimal.ZERO, BigDecimal::add)
        Column(Modifier.fillMaxWidth().background(Palette.panel).padding(14.dp)) {
            Lbl("总资产估值 (${vm.positions.firstOrNull()?.quote ?: (if (vm.posQuote == "ALL") "USDT" else vm.posQuote)}${if (vm.posQuote == "ALL") "，USDT+USDC折合" else ""})"); Text(total.f(2), color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Lbl("未实现盈亏 ${if (pnl.signum() >= 0) "+" else ""}${pnl.f(2)} ${vm.positions.firstOrNull()?.quote ?: "USDT"}", if (pnl.signum() >= 0) Palette.up else Palette.down, 14) }
        PosQuoteChips(vm)
        Row(verticalAlignment = Alignment.CenterVertically) { Chip("持仓(${vm.positions.size})", tab == 0) { tab = 0 }; Chip("当前委托(${vm.openOrders.size})", tab == 1) { tab = 1 }
            if (tab == 1 && vm.openOrders.isNotEmpty()) TextButton({ vm.cancelAll() }) { Text("全部撤单", color = Palette.down) }
            Spacer(Modifier.weight(1f)); if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp)); TextButton({ vm.loadPositions() }) { Text("刷新") } }
        if (!vm.hasKeys()) Lbl("  请先在『API密钥』页配置 ${Exchanges.names[vm.exchangeId]} 密钥", s = 14)
        if (tab == 0) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).background(Palette.panel, RoundedCornerShape(6.dp)).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("仓位计算器 · ${vm.pair}", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(if (riskOpen) "收起" else "展开", color = Palette.accent, fontSize = 12.sp, modifier = Modifier.clickable { riskOpen = !riskOpen }.padding(4.dp)) }
                if (riskOpen) RiskTpSlSection(vm, compact = false) }
            vm.positions.forEach { p -> PositionCard(p, onTpSl = if (p.asset in Quotes.STABLE) null else ({ tpslFor = p }), rule = com.morris.spottrader.pos.TpSlManager.forPair(vm.exchangeId, SymPair(p.asset, p.quote))) }
            val done = com.morris.spottrader.pos.TpSlManager.rules.filter { !it.active }.take(5)
            if (done.isNotEmpty()) { Row(Modifier.padding(start = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) { Lbl("TP/SL 历史", s = 12); TextButton({ com.morris.spottrader.pos.TpSlManager.clearInactive() }) { Text("清除", fontSize = 11.sp) } }
                done.forEach { Box(Modifier.padding(horizontal = 10.dp)) { TpSlStatus(it) } } } }
        else vm.openOrders.forEach { o -> OrderRow(o) { vm.cancel(o) } }
        Lbl("  均价按所选计价币种(USDT/USDC)的成交记录移动平均计算，盈亏按成交记录覆盖的数量计算；USDT/USDC互转优先用交易所实时汇率，否则按1:1。交易所只返回近期成交时可能不准确。", s = 11)
    }
}

@Composable private fun Col(k: String, v: String, m: Modifier) = Column(m) { Lbl(k, s = 11); Text(v, color = Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }

@Composable
fun KeysScreen(vm: AppVM) {
    val ex = vm.exchangeId; val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val stored = remember(ex, vm.keysVersion) { vm.repo.get(ex) }
    var apiKey by remember(ex, vm.keysVersion) { mutableStateOf(stored?.apiKey ?: "") }
    var secret by remember(ex, vm.keysVersion) { mutableStateOf("") }
    var pass by remember(ex, vm.keysVersion) { mutableStateOf("") }
    var type by remember(ex, vm.keysVersion) { mutableStateOf(stored?.keyType ?: "HMAC") }
    var privDer by remember(ex, vm.keysVersion) { mutableStateOf<ByteArray?>(null) }
    var pubPem by remember(ex, vm.keysVersion) { mutableStateOf(stored?.rsaPub ?: "") }
    var importPem by remember(ex) { mutableStateOf("") }
    var usePw by remember(ex) { mutableStateOf(false) }
    var pw by remember(ex) { mutableStateOf("") }; var showPw by remember { mutableStateOf(false) }
    var unlockPw by remember { mutableStateOf("") }; var generating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("${Exchanges.names[ex]} API 配置", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Lbl(if (stored != null) "已保存（${stored.keyType}）· API Key ${stored.apiKey.take(4)}****${stored.apiKey.takeLast(4)}" else "未配置")
        Lbl(if (ex == "hyperliquid") "建议：在 app.hyperliquid.xyz → API 创建 API 钱包（只能交易、不能提现），此处填主账户地址与 API 钱包私钥。" else "建议：仅开启『现货交易』与『读取』权限，禁止提现，并绑定IP白名单。")
        if (ex == "binance") Row { Chip("HMAC (Key+Secret)", type == "HMAC") { type = "HMAC" }; Chip("RSA 密钥", type == "RSA") { type = "RSA" } }
        OutlinedTextField(apiKey, { apiKey = it.trim() }, Modifier.fillMaxWidth(), label = { Text(if (ex == "hyperliquid") "主账户钱包地址 0x…（留空=由私钥推导）" else "API Key") }, singleLine = true)
        if (type == "HMAC" || ex != "binance") OutlinedTextField(secret, { secret = it.trim() }, Modifier.fillMaxWidth(), label = { Text((if (ex == "hyperliquid") "API钱包私钥 0x…" else "Secret Key") + if (stored != null) "（留空则保持不变）" else "") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        if (Exchanges.needsPassphrase(ex)) OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text(if (stored != null) "Passphrase（留空则保持不变）" else "Passphrase") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        if (ex == "binance" && type == "RSA") {
            Spacer(Modifier.height(8.dp))
            Button({ generating = true; scope.launch { val kp = withContext(Dispatchers.Default) { Crypto.generateRsa2048() }; privDer = kp.privPkcs8; pubPem = kp.publicPem; generating = false
                vm.message = "✓ 已生成RSA-2048公钥与私钥（尚未保存，请点击下方『保存』）" } }, enabled = !generating) { Text(if (generating) "生成中…" else "一键生成 RSA-2048 密钥对") }
            OutlinedTextField(importPem, { importPem = it }, Modifier.fillMaxWidth().height(110.dp), label = { Text("或粘贴已有 PKCS#8 私钥 PEM") })
            if (importPem.isNotBlank()) TextButton({ try { val d = if (importPem.contains("SPOTTRADER ENCRYPTED")) Crypto.decryptPem(importPem, pw) else Crypto.parsePkcs8Pem(importPem); privDer = d; pubPem = Crypto.publicFromPrivate(d); importPem = ""; vm.message = "私钥已导入" } catch (e: Exception) { vm.message = "私钥无效: ${e.message}" } }) { Text("导入私钥") }
            if (pubPem.isNotBlank()) {
                Lbl("公钥（注册到 Binance → API管理 → 创建API → 自行生成RSA）:")
                SelectionContainer { Text(pubPem, color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.background(Palette.field).padding(6.dp)) }
                Row { TextButton({ copy(ctx, "public key", pubPem); vm.message = "公钥已复制" }) { Text("复制公钥") }
                    TextButton({ val f = File(ctx.cacheDir, "binance_rsa_public.pem").apply { writeText(pubPem) }
                        val uri = FileProvider.getUriForFile(ctx, "com.morris.spottrader.files", f)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "导出公钥")) }) { Text("导出 .pem") } } }
            privDer?.let { d ->
                Spacer(Modifier.height(6.dp))
                Text("✓ 公钥已生成   ✓ 私钥已生成（PKCS#8, 2048位）", color = Palette.up, fontWeight = FontWeight.Bold)
                Lbl("⚠ 私钥尚未保存到本机安全存储，请点击下方『保存』。离开本页前未保存将丢失。", Palette.down, 12)
                PrivateKeyPanel(vm, d, pw.takeIf { usePw && it.length >= 8 })
            }
            if (privDer == null && stored?.keyType == "RSA") {
                var viewDer by remember(ex, vm.keysVersion) { mutableStateOf<ByteArray?>(null) }
                Spacer(Modifier.height(6.dp))
                Text("✓ 已保存私钥" + if (stored.rsaProtected) "（密码加密）" else "", color = Palette.up)
                if (viewDer == null) TextButton({ viewDer = vm.repo.privateKeyDer(ex); if (viewDer == null) vm.message = "请先用密码解锁私钥" }) { Text("查看/导出已保存私钥") }
                else { PrivateKeyPanel(vm, viewDer!!, pw.takeIf { usePw && it.length >= 8 }); TextButton({ viewDer = null }) { Text("收起") } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(usePw, { usePw = it }); Text("用密码加密私钥（每次启动需解锁）", color = Palette.text) }
            if (usePw) {
                OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("私钥密码（至少8位）") }, singleLine = true,
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation())
                Row { TextButton({ pw = Crypto.strongPassword(); showPw = true }) { Text("生成强密码") }; TextButton({ showPw = !showPw }) { Text(if (showPw) "隐藏" else "显示") }
                    TextButton({ copy(ctx, "pw", pw, true); vm.message = "密码已复制，请妥善保存" }) { Text("复制") } }
                Lbl("密码不会被保存，遗忘后需重新生成密钥。")
            }
            if (stored?.keyType == "RSA" && stored.rsaProtected && vm.repo.isLocked(ex)) {
                Spacer(Modifier.height(8.dp)); Lbl("私钥已加密，需解锁后才能交易", Palette.down, 13)
                OutlinedTextField(unlockPw, { unlockPw = it }, Modifier.fillMaxWidth(), label = { Text("输入私钥密码解锁") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Button({ scope.launch { val ok = withContext(Dispatchers.Default) { vm.repo.unlock(ex, unlockPw) }; unlockPw = ""
                    vm.message = if (ok) "已解锁" else "密码错误"; if (ok) { vm.keysVersion++; vm.refreshAll() } } }) { Text("解锁") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Button({
                val isRsa = ex == "binance" && type == "RSA"
                when {
                    apiKey.isBlank() && ex != "hyperliquid" -> vm.message = "请输入API Key"
                    !isRsa && secret.isBlank() && stored?.secret.isNullOrBlank() -> vm.message = "请输入Secret"
                    Exchanges.needsPassphrase(ex) && pass.isBlank() && stored?.passphrase.isNullOrBlank() -> vm.message = "请输入Passphrase"
                    isRsa && privDer == null && stored?.keyType != "RSA" -> vm.message = "请先生成或导入RSA私钥"
                    isRsa && usePw && privDer != null && pw.length < 8 -> vm.message = "密码至少8位"
                    else -> scope.launch {
                        val d = privDer
                        val (privStr, prot) = if (isRsa && d != null) withContext(Dispatchers.Default) {
                            if (usePw) Crypto.encryptWithPassword(d, pw) to true else java.util.Base64.getEncoder().encodeToString(d) to false }
                            else (stored?.rsaPriv ?: "") to (stored?.rsaProtected ?: false)
                        val ak = if (ex == "hyperliquid" && apiKey.isBlank()) runCatching { com.morris.spottrader.ex.EthSign.address(secret.ifBlank { stored?.secret ?: "" }) }.getOrDefault("") else apiKey
                        vm.repo.save(StoredKey(ex, ak, if (isRsa) "" else secret.ifBlank { stored?.secret ?: "" }, pass.ifBlank { stored?.passphrase ?: "" },
                            if (isRsa) "RSA" else "HMAC", if (isRsa) privStr else "", if (isRsa) prot else false, if (isRsa) pubPem else ""))
                        if (isRsa && prot && d != null) vm.repo.unlock(ex, pw)
                        pw = ""; secret = ""; pass = ""; privDer = null; vm.keysVersion++; vm.message = "已安全保存"; vm.refreshAll() }
                } }) { Text("保存") }
            Spacer(Modifier.width(8.dp))
            if (stored != null) OutlinedButton({ vm.repo.delete(ex); vm.keysVersion++; vm.message = "已删除" }) { Text("删除密钥", color = Palette.down) }
            Spacer(Modifier.width(8.dp))
            if (stored != null) OutlinedButton({ vm.loadAccount(); vm.message = "正在测试连接…" }) { Text("测试") }
        }
        Spacer(Modifier.height(16.dp))
        Lbl("存储：Android Keystore 主密钥 + EncryptedSharedPreferences (AES-256-GCM)。应用禁止截屏，不记录日志，不备份。", s = 11)
    }
}

@Composable
fun NetScreen() {
    val ctx = LocalContext.current
    var local by remember { mutableStateOf(NetInfo.localIps()) }
    var pub4 by remember { mutableStateOf("检测中…") }; var pub6 by remember { mutableStateOf("检测中…") }
    var tick by remember { mutableIntStateOf(0) }; var lic by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { local = NetInfo.localIps(); pub4 = NetInfo.publicIpv4(); pub6 = NetInfo.publicIpv6() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("IP 白名单助手", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Lbl("交易所校验的是『公网出口IP』，请把它填入API的IP白名单。移动网络出口IP可能变化，建议使用固定IP的WiFi/代理。")
        Spacer(Modifier.height(12.dp))
        Lbl("公网出口 IPv4 (api.ipify.org)"); Row(verticalAlignment = Alignment.CenterVertically) { Text(pub4, color = Palette.accent, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
            TextButton({ copy(ctx, "ip", pub4) }) { Text("复制") } }
        Lbl("公网出口 IPv6/首选 (api64.ipify.org)"); Row(verticalAlignment = Alignment.CenterVertically) { Text(pub6, color = Palette.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            TextButton({ copy(ctx, "ip", pub6) }) { Text("复制") } }
        Spacer(Modifier.height(12.dp)); Lbl("本机局域网 IP")
        if (local.isEmpty()) Lbl("无") else local.forEach { Text(it, color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
        Spacer(Modifier.height(12.dp)); Button({ pub4 = "检测中…"; pub6 = "检测中…"; tick++ }) { Text("重新检测") }
        Spacer(Modifier.height(20.dp)); HorizontalDivider(color = Palette.line)
        TextButton({ lic = true }) { Text("关于 · 开源许可 (KLineChart Apache-2.0 / Jesse MIT)") }
    }
    if (lic) LicensesDialog { lic = false }
}

@Composable
fun PrivateKeyPanel(vm: AppVM, der: ByteArray, exportPassword: String?) {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(false) }
    val pem = remember(der) { Crypto.pem("PRIVATE KEY", der) }
    Column(Modifier.fillMaxWidth().background(Palette.field).padding(8.dp)) {
        Text("私钥 (PKCS#8 PEM)", color = Palette.text, fontWeight = FontWeight.Bold)
        Lbl("⚠ 安全警告：私钥等同于账户交易权限。切勿截图、发送给他人或上传云端；仅在需要备份时导出，并妥善离线保存。", Palette.down, 12)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(show, { show = it }); Text(if (show) " 隐藏私钥" else " 显示私钥", color = Palette.text) }
        if (show) SelectionContainer { Text(pem, color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
        else Text("-----BEGIN PRIVATE KEY-----\n••••••••（已隐藏）\n-----END PRIVATE KEY-----", color = Palette.dim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Row {
            TextButton({ copy(ctx, "private key", pem, true); vm.message = "私钥已复制（敏感），用完请清空剪贴板" }) { Text("复制私钥") }
            TextButton({ sharePem(ctx, "binance_rsa_private.pem", pem, "导出私钥") }) { Text("导出 .pem") }
        }
        if (exportPassword != null) {
            TextButton({ sharePem(ctx, "binance_rsa_private.encrypted.pem", Crypto.encryptedPem(der, exportPassword), "导出加密私钥") }) { Text("导出加密 .pem（使用上方密码）") }
            Lbl("加密导出：PBKDF2-SHA256(210000次)+AES-256-GCM，可在本应用『粘贴私钥』处配合密码导入。", s = 11)
        } else Lbl("提示：勾选『用密码加密私钥』并设置密码后，可导出加密版本。", s = 11)
    }
}

fun sharePem(ctx: Context, name: String, content: String, title: String) {
    val dir = File(ctx.cacheDir, "export").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val f = File(dir, name).apply { writeText(content) }
    val uri = FileProvider.getUriForFile(ctx, "com.morris.spottrader.files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/x-pem-file").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), title))
}
