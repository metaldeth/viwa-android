package com.viwa.android.ui.debug

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viwa.android.ui.theme.MontserratFamily

const val DEV_CLIENT_CARD_PHONE = "+79220389216"
const val DEV_CLIENT_CARD_UUID = "7f84e317-cd1b-4a15-a4f6-d0189ea14019"

@Composable
fun DevClientCardScanOverlay(
    visible: Boolean,
    onEmulateScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    TextButton(
        onClick = onEmulateScan,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Rounded.QrCodeScanner,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Карта +7922",
            fontSize = 13.sp,
            fontFamily = MontserratFamily,
            fontWeight = FontWeight.Medium,
        )
    }
}
