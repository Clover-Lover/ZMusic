package com.kite.zmusic.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kite.zmusic.ZMusicApplication
import com.kite.zmusic.listen.ListenAvatarLayout
import com.kite.zmusic.listen.ListenChatMsg
import com.kite.zmusic.listen.ListenChatToast
import com.kite.zmusic.listen.ListenMember
import com.kite.zmusic.listen.ListenRoomSnapshot
import com.kite.zmusic.listen.listenAvatarLayout
import com.kite.zmusic.listen.listenChatBubbleText
import com.kite.zmusic.listen.listenChatIsSelf
import com.kite.zmusic.listen.listenChatUidEquals
import com.kite.zmusic.listen.ncmUserId
import com.kite.zmusic.ui.common.UrlImage
import com.kite.zmusic.ui.notice.showIslandNotice
import kotlinx.coroutines.delay
import com.kite.zmusic.i18n.t

private val ClusterRing = Color(0x66F2EDE6)
private val ClusterFill = Color(0x33000000)
private val ListenClusterAnim = tween<Float>(durationMillis = 320, easing = FastOutSlowInEasing)
private val ListenClusterDpAnim = tween<Dp>(durationMillis = 320, easing = FastOutSlowInEasing)
private val TelegramOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private val TelegramIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)
private val BubbleEnter = tween<Float>(durationMillis = 220, easing = TelegramOut)
private val BubbleExit = tween<Float>(durationMillis = 280, easing = TelegramIn)
private const val BubbleHoldMs = 3_200L

@Composable
internal fun PortraitListenTogetherAvatars(
    compact: Boolean,
    onOpenUser: (Long, String, String?) -> Unit,
    onOpenListenTogether: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ZMusicApplication
    val ui by app.listenTogether.ui.collectAsStateWithLifecycle()
    var held by remember { mutableStateOf<ListenRoomSnapshot?>(null) }
    val live = ui.room
    SideEffect {
        if (live != null && !live.closed) {
            held = live
        }
    }
    val room = live ?: held
    AnimatedVisibility(
        visible = ui.inRoom && room != null,
        modifier = modifier.playerExpandListenCluster(),
        enter = fadeIn(ListenClusterAnim) +
            scaleIn(
                initialScale = 0.90f,
                animationSpec = ListenClusterAnim,
                transformOrigin = TransformOrigin(0.5f, 0f),
            ),
        exit = fadeOut(tween(220, easing = FastOutSlowInEasing)) +
            scaleOut(
                targetScale = 0.90f,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                transformOrigin = TransformOrigin(0.5f, 0f),
            ),
        label = "listenTogetherCluster",
    ) {
        val shown = room ?: return@AnimatedVisibility
        ListenTogetherAvatarCluster(
            room = shown,
            compact = compact,
            selfUid = ui.selfUid,
            toasts = ui.chatToasts,
            onOpenUser = onOpenUser,
            onOpenListenTogether = onOpenListenTogether,
            onClearToast = { app.listenTogether.clearChatToast(it) },
        )
    }
}

@Composable
private fun ListenTogetherAvatarCluster(
    room: ListenRoomSnapshot,
    compact: Boolean,
    selfUid: String,
    toasts: List<ListenChatToast>,
    onOpenUser: (Long, String, String?) -> Unit,
    onOpenListenTogether: () -> Unit,
    onClearToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val layout = remember(room.members, room.hostUid) {
        listenAvatarLayout(room.members)
    }
    val host = layout.host ?: return
    val avatar by animateDpAsState(
        targetValue = if (compact) 33.6.dp else 48.dp,
        animationSpec = ListenClusterDpAnim,
        label = "listenAvatarSize",
    )
    val overlap by animateDpAsState(
        targetValue = if (compact) 12.dp else 16.8.dp,
        animationSpec = ListenClusterDpAnim,
        label = "listenAvatarOverlap",
    )
    val captionSp by animateFloatAsState(
        targetValue = if (compact) 12f else 13.2f,
        animationSpec = ListenClusterAnim,
        label = "listenCaptionSize",
    )
    val step = avatar - overlap
    val extra = (if (layout.overflow > 0) 1 else 0) + (if (layout.waitingSlot) 1 else 0)
    val count = layout.behind.size + 1 + extra
    val stackW = avatar + step * (count - 1).coerceAtLeast(0)
    val caption = when {
        layout.waitingSlot -> t("等待加入 · 1/%s", room.maxMembers.coerceAtLeast(2))
        else -> t("一起听 · %s人", room.members.size)
    }
    val toastIndex = remember(toasts, selfUid, layout.host?.uid, layout.behind, layout.overflow) {
        toasts.associate { item ->
            item.key to layout.slotIndex(item.msg.uid, host, selfUid, item.msg)
        }
    }
    var renderedToasts by remember { mutableStateOf(toasts) }
    LaunchedEffect(toasts) {
        val live = toasts.associateBy { it.key }
        val kept = renderedToasts.map { live[it.key] ?: it }
        val added = toasts.filter { item -> kept.none { it.key == item.key } }
        renderedToasts = kept + added
    }

    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = stackW, height = avatar)) {
            var slot = 0
            if (layout.overflow > 0) {
                OverflowDot(
                    extra = layout.overflow,
                    size = avatar,
                    modifier = Modifier
                        .offset(x = step * slot)
                        .zIndex(0f)
                        .clickableNoRipple(onOpenListenTogether),
                )
                slot++
            }
            layout.behind.forEachIndexed { i, member ->
                MemberDot(
                    member = member,
                    size = avatar,
                    host = false,
                    modifier = Modifier
                        .offset(x = step * slot)
                        .zIndex((i + 1).toFloat())
                        .clickableNoRipple {
                            openMemberSpace(context, member, onOpenUser)
                        },
                )
                slot++
            }
            MemberDot(
                member = host,
                size = avatar,
                host = true,
                modifier = Modifier
                    .offset(x = step * slot)
                    .zIndex(20f)
                    .clickableNoRipple {
                        openMemberSpace(context, host, onOpenUser)
                    },
            )
            slot++
            if (layout.waitingSlot) {
                WaitingDot(
                    size = avatar,
                    modifier = Modifier
                        .offset(x = step * slot)
                        .zIndex(1f)
                        .clickableNoRipple(onOpenListenTogether),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val paneWidth = maxWidth
            val stackLeft = (paneWidth - stackW) / 2
            Column(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(tween(200, easing = TelegramOut)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                renderedToasts.forEach { item ->
                    key(item.key) {
                        val idx = toastIndex[item.key]
                            ?: layout.slotIndex(item.msg.uid, host, selfUid, item.msg)
                        val avatarCenter = if (idx >= 0) {
                            stackLeft + step * idx + avatar / 2
                        } else {
                            paneWidth / 2
                        }
                        val dxPx = with(density) { (avatarCenter - paneWidth / 2).toPx() }
                        Box(Modifier.fillMaxWidth()) {
                            Box(
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .graphicsLayer {
                                        translationX = dxPx
                                        clip = false
                                    },
                            ) {
                                ListenChatToastItem(
                                    item = item,
                                    alive = toasts.any { it.key == item.key },
                                    onClear = onClearToast,
                                    onGone = {
                                        renderedToasts = renderedToasts.filter { it.key != item.key }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (renderedToasts.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = caption,
            style = TextStyle(
                color = LyricCurrent.copy(alpha = 0.78f),
                fontWeight = FontWeight.Medium,
                fontSize = captionSp.sp,
                letterSpacing = 0.2.sp,
            ),
            modifier = Modifier.clickableNoRipple(onOpenListenTogether),
        )
    }
}

private fun ListenAvatarLayout.slotIndex(
    uid: String,
    host: ListenMember,
    selfUid: String = "",
    msg: ListenChatMsg? = null,
): Int {
    if (uid.isBlank() && msg == null) return -1
    var i = 0
    if (overflow > 0) i++
    behind.forEach { member ->
        if (listenChatUidEquals(member.uid, uid)) return i
        i++
    }
    if (listenChatUidEquals(host.uid, uid)) return i
    if (msg != null && listenChatIsSelf(msg, selfUid)) {
        val selfHit = slotIndex(selfUid, host)
        if (selfHit >= 0) return selfHit
        return slotIndex(host.uid, host)
    }
    return -1
}

@Composable
private fun ListenChatToastItem(
    item: ListenChatToast,
    alive: Boolean,
    onClear: (String) -> Unit,
    onGone: () -> Unit,
) {
    val visible = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    LaunchedEffect(alive) {
        visible.targetState = alive
    }
    LaunchedEffect(item.key) {
        delay(BubbleHoldMs)
        onClear(item.key)
    }
    LaunchedEffect(visible.isIdle, visible.targetState, alive) {
        if (visible.isIdle && !visible.targetState && !alive) {
            onGone()
        }
    }
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(BubbleEnter) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec = BubbleEnter,
                transformOrigin = TransformOrigin(0.5f, 1f),
            ),
        exit = fadeOut(BubbleExit) +
            scaleOut(
                targetScale = 0.96f,
                animationSpec = BubbleExit,
                transformOrigin = TransformOrigin(0.5f, 0f),
            ),
        label = "listenChatToast:${item.key}",
    ) {
        ListenChatToastBubble(text = listenChatBubbleText(item.msg.text))
    }
}

@Composable
private fun ListenChatToastBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xF2FFF7F0))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = Color(0xFF1A1512),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}

@Composable
private fun MemberDot(
    member: ListenMember,
    size: Dp,
    host: Boolean,
    modifier: Modifier = Modifier,
) {
    val ring = if (host) LyricCurrent.copy(alpha = 0.95f) else ClusterRing
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .border(if (host) 2.4.dp else 1.8.dp, ring, CircleShape)
            .background(ClusterFill),
    ) {
        if (member.avatarUrl.isNotBlank()) {
            UrlImage(
                url = member.avatarUrl,
                contentDescription = member.nickname,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = member.nickname.trim().take(1).ifBlank { "?" },
                color = LyricCurrent,
                fontSize = 15.6.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun WaitingDot(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .border(1.5.dp, ClusterRing, CircleShape)
            .background(ClusterFill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+",
            color = LyricCurrent.copy(alpha = 0.85f),
            fontSize = 19.2.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun OverflowDot(
    extra: Int,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .border(1.5.dp, ClusterRing, CircleShape)
            .background(ClusterFill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+${extra.coerceAtMost(9)}",
            color = LyricCurrent,
            fontSize = 13.2.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

private fun openMemberSpace(
    context: android.content.Context,
    member: ListenMember,
    onOpenUser: (Long, String, String?) -> Unit,
) {
    val id = member.ncmUserId()
    if (id == null) {
        context.showIslandNotice(t("无法打开主页"))
        return
    }
    onOpenUser(id, member.nickname, member.avatarUrl.takeIf { it.isNotBlank() })
}
