package com.kite.zmusic.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kite.zmusic.i18n.t
import com.kite.zmusic.ui.common.rememberPredictiveBackUi
import com.kite.zmusic.ui.main.MainPalette
import kotlinx.coroutines.delay

private val ConfirmShape = RoundedCornerShape(32.dp)
private val ConfirmPopEasing = CubicBezierEasing(0.16f, 1.12f, 0.28f, 1f)
private val ConfirmHideEasing = CubicBezierEasing(0.4f, 0.02f, 0.2f, 1f)
private val OnAccent = Color.White

@Composable
fun UnlockGrayDisclaimerPage(
    contentBottomInset: Dp,
    onRequestConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val atBottom by remember {
        derivedStateOf { scroll.value >= scroll.maxValue }
    }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp)
            .padding(bottom = contentBottomInset + 28.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MainPalette.Accent)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = t("警告 · 明确禁止"),
                style = TextStyle(
                    color = OnAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = t("ZMusic 明确禁止开启此功能"),
                style = TextStyle(
                    color = OnAccent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 24.sp,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = t("请完整阅读以下说明。这不是建议，也不是「甚至不建议」。关闭是唯一被允许的状态。你必须滚动到页面最底部，底部那一行短文本才会可点。"),
            style = TextStyle(
                color = MainPalette.Secondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            ),
        )
        Spacer(Modifier.height(22.dp))
        DisclaimerSection(
            index = 1,
            heading = t("禁止，不是劝退"),
            body = t("ZMusic 明确禁止开启「歌曲解灰」。设置里出现开关，只为让你在知情后保持关闭或离开本页，不构成许可、授权、默示同意或功能承诺。任何教程、口口相传或二次修改，都不能把禁止改成允许。无视本页并强制打开，是你单方面违反本应用立场。"),
        )
        DisclaimerSection(
            index = 2,
            heading = t("这是侵权风险，不是官方能力"),
            body = t("启用解灰属于侵犯网易云音乐及其权利人权利与服务规则的行为。灰色、无版权、无音源、下架或需会员才能播放的曲目，不会因为被解开而变成你已获授权的内容。它也不能代替 VIP、购买、下载或官方客户端。ZMusic 不允许把它宣传成本应用的能力或卖点。"),
        )
        DisclaimerSection(
            index = 3,
            heading = t("逆向来自开源库，ZMusic 没有自行逆向"),
            body = t("ZMusic 没有自行逆向网易云音乐客户端、EAPI、灰色歌曲匹配或其它平台音源协议。解灰所依赖的逆向成果、匹配与音源替换，来自 UnblockNeteaseMusic 等外部开源项目；你所配置的兼容 API 服务（例如接入了上述项目的 NeteaseCloudMusicApiEnhanced 一类实现）把这些能力以 /song/url/match 等接口暴露出来。ZMusic 客户端在你无视禁止之后，至多把「官方无音源」的失败转交给这些外部能力再试一次。\n\n指出「逆向在开源库」是为了陈述技术事实：谁做了逆向，谁就做了逆向。这不是把 ZMusic 洗成无关方，也不是暗示开源库在替你或替 ZMusic 侵权。"),
        )
        DisclaimerSection(
            index = 4,
            heading = t("必须同时写明开源库的立场，禁止甩锅"),
            body = t("只写 ZMusic 的免责、把逆向与风险都推到库上，开源库作者有理由把这看成甩锅。因此本页必须同时转述他们已经公开的立场，而不是只保护 ZMusic。\n\n就 UnblockNeteaseMusic 等项目已经公开的说明与许可证而言（以其仓库原文为准，ZMusic 无权代为扩大、缩小或改写其法律效果）：该项目公开的是解锁客户端变灰歌曲的技术实现，并对社区在 EAPI 等协议上的逆向工作致谢。它使用 MIT 等许可，按「按现状提供、不附带任何保证」分发。作者警告不要轻易信任他人提供的公开代理，以免发生安全问题，并要求在公网部署时限制范围，防止能力被滥用。\n\n这些内容不是对侵权的授权。开源许可里的无担保条款，处理的是软件质量与许可关系，并不能替使用者、ZMusic 或任何二次封装者承担侵犯版权、违反服务条款或行政处罚的责任。开源库作者没有同意为 ZMusic 的入口背书，没有同意成为「解灰责任人」，也没有把他们的逆向工作授权给你用来侵权。ZMusic 不得、也不会把他们写成替罪方。"),
        )
        DisclaimerSection(
            index = 5,
            heading = t("责任在你。ZMusic 与开源库作者都不替你承担"),
            body = t("开启、调用、继续使用所产生的封号、索赔、侵权纠纷、服务中断、安全事故或任何法律后果，均由你自行承担。你不能主张「是 ZMusic 让我开的」，也不能主张「是开源库让我开的」。ZMusic 作者与 UnblockNeteaseMusic 等开源库作者，都不对解灰结果、音源来源、音质、可用性或合法性作出保证，也都不因你无视禁止而成为责任人或约定的分担方。"),
        )
        DisclaimerSection(
            index = 6,
            heading = t("若你仍强制开启，客户端实际会做什么"),
            body = t("仅当官方接口可以明确判定为无音源、无版权链接等无法播放，且本机也没有缓存时，才会尝试外部解灰。再次失败将跳过该曲。成功时会用灵动岛通知你：已经对此歌曲进行了成功解灰。该通知只说明尝试结果，不表示播放合法，也不表示禁止被撤销。"),
        )
        DisclaimerSection(
            index = 7,
            heading = t("强制开启不能使禁止失效"),
            body = t("即使你完成本页滚动、点选继续并再次确认，ZMusic 仍然禁止此项。入口保持关闭才是被允许的状态。你随后的每一次解灰请求，都是你在明知禁止的情况下重复实施的行为。"),
        )
        Spacer(Modifier.height(8.dp))
        UnlockGrayCancelButton(onClick = onCancel)
        Spacer(Modifier.height(20.dp))
        Text(
            text = t("下面不是按钮。点选下一行短文本，即表示你已完整阅读本页，明知 ZMusic 明确禁止、开源库作者不背书、侵权与法律责任由你本人承担，仍为自身使用需求强制开启。"),
            style = TextStyle(
                color = MainPalette.Secondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            ),
        )
        Spacer(Modifier.height(4.dp))
        val acceptEnabled = atBottom
        Text(
            text = t("无视禁止并继续"),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = acceptEnabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRequestConfirm,
                )
                .padding(vertical = 10.dp),
            style = TextStyle(
                color = if (acceptEnabled) MainPalette.Ink else MainPalette.Hint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                textDecoration = if (acceptEnabled) {
                    TextDecoration.Underline
                } else {
                    TextDecoration.None
                },
            ),
        )
        if (!acceptEnabled) {
            Text(
                text = t("请先滚动到页面底部"),
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(
                    color = MainPalette.Hint,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@Composable
internal fun UnlockGrayConfirmOverlay(
    visible: Boolean,
    landscape: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hosted by remember { mutableStateOf(false) }
    SideEffect {
        if (visible) hosted = true
    }
    LaunchedEffect(visible) {
        if (!visible && hosted) {
            delay(200)
            if (!visible) hosted = false
        }
    }
    if (!hosted) return

    val backUi = rememberPredictiveBackUi(enabled = visible, onBack = onDismiss)
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(320, easing = ConfirmPopEasing))
        } else {
            reveal.animateTo(0f, tween(160, easing = ConfirmHideEasing))
        }
    }
    val t = reveal.value * (1f - backUi.progress)
    Box(
        modifier
            .fillMaxSize()
            .zIndex(8f)
            .graphicsLayer { alpha = t },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            Modifier
                .padding(horizontal = if (landscape) 48.dp else 28.dp)
                .widthIn(min = 300.dp, max = if (landscape) 400.dp else 360.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val s = 0.92f + 0.08f * t
                    scaleX = s
                    scaleY = s
                }
                .clip(ConfirmShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MainPalette.Accent)
                    .padding(horizontal = 22.dp, vertical = if (landscape) 16.dp else 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = t("警告"),
                        style = TextStyle(
                            color = OnAccent.copy(alpha = 0.86f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = t("再次确认"),
                        style = TextStyle(
                            color = OnAccent,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            letterSpacing = (-0.3).sp,
                            lineHeight = 26.sp,
                        ),
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MainPalette.Surface)
                    .padding(horizontal = 22.dp)
                    .padding(
                        top = if (landscape) 16.dp else 20.dp,
                        bottom = if (landscape) 18.dp else 22.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = t("我再次确定开启此功能，并保证为使用者本人操作。ZMusic 仍然禁止此事，开源库作者也不会因此成为责任人。"),
                    style = TextStyle(
                        color = MainPalette.Ink,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = t("无视禁止并继续"),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onConfirm,
                        )
                        .padding(vertical = 12.dp),
                    style = TextStyle(
                        color = MainPalette.Secondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        textDecoration = TextDecoration.Underline,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                UnlockGrayCancelButton(
                    label = t("取消"),
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun UnlockGrayCancelButton(
    onClick: () -> Unit,
    label: String = t("取消并保持关闭"),
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MainPalette.Accent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = OnAccent,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

@Composable
private fun DisclaimerSection(
    index: Int,
    heading: String,
    body: String,
) {
    Text(
        text = "$index. $heading",
        style = TextStyle(
            color = MainPalette.Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 22.sp,
        ),
    )
    Spacer(Modifier.height(8.dp))
    body.split("\n\n").forEachIndexed { i, para ->
        if (i > 0) Spacer(Modifier.height(10.dp))
        Text(
            text = para,
            style = TextStyle(
                color = MainPalette.Secondary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            ),
        )
    }
    Spacer(Modifier.height(22.dp))
}
