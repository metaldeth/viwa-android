package com.viwa.android.ui.screens.customer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viwa.android.R
import com.viwa.android.ui.components.QRCodeView
import com.viwa.android.ui.theme.MontserratFamily

private val OfferBackground = Color(0xFF09090C)
private val OfferSurface = Color(0xE617171C)
private val OfferAccent = Color(0xFFC600FF)
private val OfferAccentMuted = Color(0xFF422050)
private val OfferText = Color(0xFFF7F5F8)
private val OfferTextMuted = Color(0xFFB7B1BA)
private val OfferQrPlaceholder = Color(0xFF3B3440)

private data class Benefit(
    val icon: ImageVector,
    val label: String,
)

private val benefits =
    listOf(
        Benefit(Icons.Rounded.Spa, "Лёгкий вкус"),
        Benefit(Icons.Rounded.Bolt, "Витамины B3, B6 и B12"),
        Benefit(Icons.Rounded.WaterDrop, "Минералы Mg и Zn"),
        Benefit(Icons.Rounded.Favorite, "Без сахара и калорий"),
    )

/**
 * Промо бесплатной воды. QR открывает web-авторизацию:
 * `https://cabinet.vitamin-water.ru/m/{serial}/auth`.
 */
@Composable
fun FreeDrinkOfferScreen(
    onClose: () -> Unit,
    viewModel: FreeDrinkOfferViewModel = hiltViewModel(),
) {
    val qrUrl by viewModel.qrUrl.collectAsStateWithLifecycle()

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(OfferBackground),
    ) {
        val scale = minOf(maxWidth / 1024.dp, maxHeight / 768.dp)

        Image(
            painter = painterResource(R.drawable.viwa_water_promo_dark),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd,
        )

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.98f),
                            0.48f to Color.Black.copy(alpha = 0.88f),
                            0.72f to Color.Black.copy(alpha = 0.32f),
                            1f to Color.Black.copy(alpha = 0.10f),
                        ),
                    ),
        )

        OfferContent(
            qrUrl = qrUrl,
            scale = scale,
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width(600.dp * scale)
                    .padding(
                        start = 48.dp * scale,
                        top = 38.dp * scale,
                        bottom = 38.dp * scale,
                    ),
        )

        BrandTaglineBadge(
            scale = scale,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 40.dp * scale, bottom = 36.dp * scale),
        )

        IconButton(
            onClick = onClose,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp * scale)
                    .size(48.dp * scale)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.52f))
                    .semantics {
                        contentDescription = "Закрыть"
                        role = Role.Button
                    },
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                tint = OfferText,
                modifier = Modifier.size(22.dp * scale),
            )
        }
    }
}

@Composable
private fun OfferContent(
    qrUrl: String?,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text =
                buildAnnotatedString {
                    append("Попробуй VIWA\n")
                    withStyle(SpanStyle(color = OfferAccent)) { append("бесплатно") }
                },
            style =
                TextStyle(
                    fontFamily = MontserratFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = (42f * scale).sp,
                    lineHeight = (47f * scale).sp,
                    color = OfferText,
                ),
        )

        Spacer(Modifier.height(10.dp * scale))

        Text(
            text = "Лёгкая витаминная вода из умной станции",
            modifier = Modifier.fillMaxWidth(0.88f),
            style =
                TextStyle(
                    fontFamily = MontserratFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = (16f * scale).sp,
                    lineHeight = (22f * scale).sp,
                    color = OfferTextMuted,
                ),
        )

        Spacer(Modifier.height(22.dp * scale))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp * scale)) {
            benefits.forEach { benefit ->
                BenefitRow(
                    benefit = benefit,
                    scale = scale,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        QrOfferCard(
            qrUrl = qrUrl,
            scale = scale,
        )
    }
}

@Composable
private fun BenefitRow(
    benefit: Benefit,
    scale: Float,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp * scale),
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp * scale)
                    .clip(RoundedCornerShape(10.dp * scale))
                    .background(OfferAccentMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = benefit.icon,
                contentDescription = null,
                tint = OfferAccent,
                modifier = Modifier.size(17.dp * scale),
            )
        }
        Text(
            text = benefit.label,
            style =
                TextStyle(
                    fontFamily = MontserratFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (15f * scale).sp,
                    lineHeight = (19f * scale).sp,
                    color = OfferText,
                ),
        )
    }
}

@Composable
private fun QrOfferCard(
    qrUrl: String?,
    scale: Float,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(22.dp * scale))
                .background(OfferSurface)
                .padding(14.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp * scale),
    ) {
        val qrSize = 168.dp * scale
        Box(
            modifier =
                Modifier
                    .size(qrSize)
                    .clip(RoundedCornerShape(14.dp * scale))
                    .background(Color.White)
                    .padding(8.dp * scale),
            contentAlignment = Alignment.Center,
        ) {
            if (qrUrl != null) {
                QRCodeView(
                    data = qrUrl,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "QR\nнедоступен",
                    fontFamily = MontserratFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = (13f * scale).sp,
                    lineHeight = (16f * scale).sp,
                    color = OfferQrPlaceholder,
                )
            }
        }

        Text(
            text = if (qrUrl != null) "Сканируй и авторизуйся" else "QR временно недоступен",
            modifier = Modifier.width(250.dp * scale),
            style =
                TextStyle(
                    fontFamily = MontserratFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = (18f * scale).sp,
                    lineHeight = (22f * scale).sp,
                    color = OfferText,
                ),
        )
    }
}

@Composable
private fun BrandTaglineBadge(
    scale: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(18.dp * scale))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 20.dp * scale, vertical = 14.dp * scale),
    ) {
        Text(
            text = "Вкус",
            fontFamily = MontserratFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (18f * scale).sp,
            color = OfferText,
        )
        Text(
            text = "в точной дозе",
            fontFamily = MontserratFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (19f * scale).sp,
            color = OfferAccent,
        )
    }
}
