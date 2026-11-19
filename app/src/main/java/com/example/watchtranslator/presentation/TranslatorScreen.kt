package com.example.watchtranslator

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

private val InputColor = Color(0xFF2F6FD0)
private val OutputColor = Color(0xFF2FA866)

@Composable
fun TranslatorScreen(vm: TranslatorViewModel = viewModel()) {
    MaterialTheme {
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Cada esfera mide ~76 % del ancho de pantalla y solo se ve su mitad
            val diameter = screenWidth * 0.76f

            val inGlow by animateFloatAsState(
                if (vm.phase == Phase.LISTENING) vm.level else 0f, label = "inGlow"
            )
            val outGlow by animateFloatAsState(
                when (vm.phase) {
                    Phase.SPEAKING -> 1f
                    Phase.TRANSLATING -> 0.5f
                    else -> 0f
                },
                label = "outGlow"
            )

            Box(modifier = Modifier.fillMaxSize()) {
                LanguageSphere(
                    lang = vm.from,
                    caption = "ENTRADA",
                    badge = modelState(vm, vm.from),
                    base = InputColor,
                    glow = inGlow,
                    left = true,
                    dir = vm.inputDir,
                    diameter = diameter,
                    onSwipe = { vm.swipe(Side.INPUT, it) }
                )
                LanguageSphere(
                    lang = vm.to,
                    caption = "SALIDA",
                    badge = modelState(vm, vm.to),
                    base = OutputColor,
                    glow = outGlow,
                    left = false,
                    dir = vm.outputDir,
                    diameter = diameter,
                    onSwipe = { vm.swipe(Side.OUTPUT, it) }
                )

                // Botón central
                Button(
                    onClick = vm::onMicTap,
                    modifier = Modifier.align(Alignment.Center).size(40.dp)
                ) {
                    Text(if (vm.phase == Phase.IDLE) "🎤" else "■", fontSize = 18.sp)
                }

                // Estado o resultado, abajo al centro
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .fillMaxWidth(0.5f)
                        .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (vm.phase == Phase.IDLE && vm.translated.isNotBlank()) {
                        Text(
                            vm.heard, fontSize = 9.sp, color = Color.LightGray,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            vm.translated, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            vm.status, fontSize = 10.sp, color = Color.White,
                            maxLines = 2, textAlign = TextAlign.Center
                        )
                    }
                }

                vm.toast?.let { ToastBanner(it) }
            }
        }
    }
}

private fun modelState(vm: TranslatorViewModel, lang: Lang): String = when {
    lang.ml in vm.downloaded -> "✓ listo"
    lang.ml in vm.downloading -> "descargando…"
    else -> "sin descargar"
}

/**
 * Esfera pegada al borde (izquierda o derecha): solo se ve la mitad.
 * Deslizar hacia ARRIBA = idioma siguiente, hacia ABAJO = anterior.
 */
@Composable
private fun BoxScope.LanguageSphere(
    lang: Lang,
    caption: String,
    badge: String,
    base: Color,
    glow: Float,
    left: Boolean,
    dir: Int,
    diameter: Dp,
    onSwipe: (Int) -> Unit
) {
    val half = diameter / 2
    var acc by remember { mutableStateOf(0f) }
    val threshold = with(LocalDensity.current) { 28.dp.toPx() }
    val edge = if (left) Alignment.CenterStart else Alignment.CenterEnd

    Box(
        modifier = Modifier
            .align(edge)
            .width(half)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = { acc = 0f },
                    onDragCancel = { acc = 0f },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        acc += dy
                        if (acc <= -threshold) { onSwipe(+1); acc = 0f }
                        else if (acc >= threshold) { onSwipe(-1); acc = 0f }
                    }
                )
            }
    ) {
        // Esfera completa, corrida hacia afuera para que solo se vea la mitad
        Box(
            modifier = Modifier
                .align(edge)
                .requiredSize(diameter)
                .offset(x = if (left) -half else half)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(listOf(base.copy(alpha = 0.55f), Color(0xFF0E0E0E)))
                )
                .border(
                    width = 3.dp,
                    color = base.copy(alpha = 0.35f + 0.65f * glow),
                    shape = CircleShape
                )
        )
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(caption, fontSize = 8.sp, color = Color.LightGray)
            // Al cambiar de idioma: la bandera y el nombre se deslizan y se difuminan
            AnimatedContent(
                targetState = lang,
                transitionSpec = {
                    (slideInVertically(tween(250)) { h -> dir * h / 2 } + fadeIn(tween(250))) togetherWith
                            (slideOutVertically(tween(200)) { h -> -dir * h / 2 } + fadeOut(tween(200)))
                },
                label = "lang"
            ) { l ->
                val blurRadius by transition.animateDp(label = "blur") { st ->
                    if (st == EnterExitState.Visible) 0.dp else 10.dp
                }
                Column(
                    modifier = Modifier.blur(blurRadius, BlurredEdgeTreatment.Unbounded),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(l.flag, fontSize = 34.sp)
                    Text(l.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Text(badge, fontSize = 8.sp, color = Color.LightGray)
        }
    }
}

@Composable
private fun BoxScope.ToastBanner(message: String) {
    Text(
        text = message,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        color = Color.White,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 18.dp, start = 28.dp, end = 28.dp)
            .background(Color(0xFF3C3C3C), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}