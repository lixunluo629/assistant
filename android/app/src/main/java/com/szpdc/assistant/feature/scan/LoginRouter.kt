package com.szpdc.assistant.feature.scan

/**
 * 决策引擎（Android 实现，与 Python 的 router.py 同表）。
 *
 * 硬性约束：
 * 1. deepLink 只取自规则表常量，绝不拼接二维码内容；
 * 2. loose 匹配一律不自动跳转；
 * 3. 未识别平台、非白名单域名一律拒绝或降级为手动；
 * 4. 本类不发起任何网络请求、不接触登录票据。
 */
object LoginRouter {

    fun route(
        raw: String,
        rules: RuleSet,
        installStatusOf: (PlatformRule) -> InstallStatus,
        environment: String = "android",
    ): Decision {
        return when (val outcome = QrParser.parse(raw, rules)) {
            is ParseOutcome.Rejected -> rejectDecision(
                outcome.reason, outcome.message, outcome.fingerprint,
            )
            is ParseOutcome.Success -> {
                val target = outcome.target
                val rule = rules.ruleFor(target.platform)
                if (rule == null) {
                    rejectDecision(
                        RejectionReason.UNKNOWN_PLATFORM, "缺少该平台的规则配置", target.fingerprint,
                    )
                } else {
                    decideForTarget(target, installStatusOf(rule), rule, environment)
                }
            }
        }
    }

    /**
     * 给"有明确跳转目标"的动作打上自动执行标记。
     *
     * OPEN_APP / PROMPT_DOWNLOAD 的下一步是确定的（打开某个 App 或商店），用户扫这个码
     * 已经表达了意图，没必要再点一次按钮；REJECT / MANUAL_SCAN 必须先让用户看到风险提示。
     */
    fun applyAutoLaunch(decision: Decision): Decision =
        if (decision.action == Action.OPEN_APP || decision.action == Action.PROMPT_DOWNLOAD) {
            decision.copy(autoLaunch = true)
        } else {
            decision
        }

    fun decideForTarget(
        target: LoginTarget,
        status: InstallStatus,
        rule: PlatformRule,
        environment: String = "android",
    ): Decision = applyAutoLaunch(decideForTargetInner(target, status, rule, environment))

    private fun decideForTargetInner(
        target: LoginTarget,
        status: InstallStatus,
        rule: PlatformRule,
        environment: String = "android",
    ): Decision {
        val name = rule.displayName
        val hint = rule.scanTargetHint

        if (target.loose) {
            return Decision(
                action = Action.MANUAL_SCAN,
                platform = target.platform,
                title = "可能是" + name + "登录二维码",
                detail = "二维码内容里没有找到官方登录链接，只是出现了与" + name +
                    "相关的文字线索，因此不做自动跳转。请打开" + name + "手动确认。",
                steps = listOf(
                    "打开" + name,
                    "进入「" + hint + "」",
                    "对准 PC 屏幕上的二维码扫描并确认登录",
                ),
                androidPackages = rule.androidPackages,
                fingerprint = target.fingerprint,
            )
        }

        return when (status) {
            InstallStatus.INSTALLED -> if (rule.scanDeepLinks.isNotEmpty() || rule.scanActivityClasses.isNotEmpty()) {
                // 少数平台（实测目前只有钉钉）对外暴露了扫一扫入口，直接把用户送到扫一扫；
                // 扫什么、确不确认登录，仍然由用户本人在官方 App 里完成。
                Decision(
                    action = Action.OPEN_APP,
                    platform = target.platform,
                    title = "已识别为" + name + "登录二维码",
                    detail = "本机已安装" + name + "，正在为你打开" + name + "的「" + hint +
                        "」。请对准 PC 屏幕上的二维码扫描，并在" + name + "内手动确认登录。",
                    deepLink = rule.deepLink, // 只来自规则表常量
                    fallbackUrl = rule.passwordLoginUrl,
                    androidPackages = rule.androidPackages,
                    scanDeepLinks = rule.scanDeepLinks,
                    scanActivityClasses = rule.scanActivityClasses,
                    steps = listOf(
                        "自动打开" + name + "的「" + hint + "」",
                        "对准 PC 屏幕上的二维码扫描",
                        "在" + name + "内手动点击「确认登录」",
                    ),
                    fingerprint = target.fingerprint,
                )
            } else {
                Decision(
                    action = Action.OPEN_APP,
                    platform = target.platform,
                    title = "已识别为" + name + "登录二维码",
                    detail = "本机已安装" + name + "，正在唤起 App。" + name +
                        "没有对外提供扫一扫快捷入口，请在" + name + "内点击" + hint +
                        "扫描 PC 屏幕，并手动确认登录。",
                    deepLink = rule.deepLink, // 只来自规则表常量
                    fallbackUrl = rule.passwordLoginUrl,
                    androidPackages = rule.androidPackages,
                    steps = listOf(
                        "唤起" + name,
                        "点击「" + hint + "」",
                        "扫描 PC 屏幕上的二维码",
                        "在" + name + "内手动点击「确认登录」",
                    ),
                    fingerprint = target.fingerprint,
                )
            }

            InstallStatus.NOT_INSTALLED -> {
                val detail = "本机未检测到" + name + "。可以下载官方 App 后重试扫码" +
                    if (rule.passwordLoginUrl != null) "，或直接改用账号密码登录。" else "。"
                Decision(
                    action = Action.PROMPT_DOWNLOAD,
                    platform = target.platform,
                    title = "未安装" + name,
                    detail = detail,
                    storeUrl = rule.storeUrlFor(environment),
                    fallbackUrl = rule.passwordLoginUrl,
                    androidPackages = rule.androidPackages,
                    steps = listOf(
                        "下载并安装官方" + name + "（仅从应用商店或官网下载）",
                        "打开" + name + "并进入「" + hint + "」",
                        "扫描 PC 屏幕上的二维码并确认登录",
                    ),
                    fingerprint = target.fingerprint,
                )
            }

            InstallStatus.UNKNOWN -> Decision(
                action = Action.MANUAL_SCAN,
                platform = target.platform,
                title = "已识别为" + name + "登录二维码",
                detail = "无法确认本机是否安装了" + name + "（系统限制了应用可见性）。" +
                    "如果你装过，直接打开" + name + "扫一扫即可；没有的话请从官方渠道安装。",
                storeUrl = rule.storeUrlFor(environment),
                fallbackUrl = rule.passwordLoginUrl,
                androidPackages = rule.androidPackages,
                steps = listOf(
                    "手动打开" + name,
                    "进入「" + hint + "」",
                    "扫描 PC 屏幕上的二维码并确认登录",
                ),
                fingerprint = target.fingerprint,
            )
        }
    }

    fun rejectDecision(
        reason: RejectionReason,
        message: String,
        fingerprint: String = "",
    ): Decision {
        if (reason == RejectionReason.UNSAFE_SCHEME) {
            return Decision(
                action = Action.REJECT,
                platform = Platform.UNKNOWN,
                title = "这个二维码不安全，已停止处理",
                detail = message + "。请不要在来路不明的页面上扫码登录。",
                rejectionReason = reason,
                fingerprint = fingerprint,
            )
        }
        if (reason == RejectionReason.EMPTY) {
            return Decision(
                action = Action.REJECT,
                platform = Platform.UNKNOWN,
                title = "没有扫到内容",
                detail = "请重新对准 PC 屏幕上的二维码。",
                rejectionReason = reason,
                fingerprint = fingerprint,
            )
        }
        return Decision(
            action = Action.MANUAL_SCAN,
            platform = Platform.UNKNOWN,
            title = "暂不支持这个二维码",
            detail = message + "。你可以手动打开对应 App 扫码，或在该网站改用账号密码登录。",
            steps = listOf(
                "确认 PC 页面上写明的登录平台",
                "手动打开对应官方 App",
                "使用 App 内的扫一扫完成登录",
            ),
            rejectionReason = reason,
            fingerprint = fingerprint,
        )
    }

    /** 用户在界面上主动选择"改用账号密码登录"。 */
    fun decideForPasswordLogin(platform: Platform, rules: RuleSet): Decision {
        val rule = rules.ruleFor(platform)
        if (rule == null || rule.passwordLoginUrl == null) {
            return Decision(
                action = Action.MANUAL_SCAN,
                platform = platform,
                title = platform.displayName + "没有账号密码登录入口",
                detail = if (rule != null && rule.passwordLoginNote.isNotEmpty()) {
                    rule.passwordLoginNote
                } else {
                    "该平台仅支持扫码登录，请回到 PC 页面重新获取二维码。"
                },
            )
        }
        return Decision(
            action = Action.PASSWORD_LOGIN,
            platform = platform,
            title = "改用" + rule.displayName + "账号密码登录",
            detail = rule.passwordLoginNote.ifEmpty { "将在浏览器中打开官方登录页。" },
            fallbackUrl = rule.passwordLoginUrl,
            androidPackages = rule.androidPackages,
            steps = listOf("在浏览器中打开官方登录页", "输入账号密码并完成二次验证"),
        )
    }
}
