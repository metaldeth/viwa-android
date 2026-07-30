package com.viwa.android.ui.screens.customer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viwa.android.R
import com.viwa.android.ui.theme.MontserratFamily

/** Статичный промо-баннер в тёмной теме. Тап открывает предложение бесплатной воды. */
@Composable
fun ViwaPromoCard(
    s: Float = 1f,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(topStart = (20f * s).dp, topEnd = (20f * s).dp)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier
                .width((495f * s).dp)
                .height((154f * s).dp)
                .viwaCardShadow(elevation = (4f * s).dp, shape = cardShape)
                .background(Color.Black, cardShape)
                .clip(cardShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
    ) {
        Image(
            painter = painterResource(R.drawable.viwa_water_promo_dark),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width((290f * s).dp)
                    .background(Color.Black.copy(alpha = 0.18f))
                    .padding(horizontal = (22f * s).dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text(
                text = "Попробуй вкусную\nи полезную воду",
                fontSize = (25f * s).sp,
                lineHeight = (28f * s).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = MontserratFamily,
                color = Color.White,
            )
            Text(
                text = "Освежись с пользой",
                modifier = Modifier.padding(top = (6f * s).dp),
                fontSize = (13f * s).sp,
                lineHeight = (16f * s).sp,
                fontWeight = FontWeight.Medium,
                fontFamily = MontserratFamily,
                color = Color(0xFFD24CFF),
            )
        }
    }
}