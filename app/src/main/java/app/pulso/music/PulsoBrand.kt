package app.pulso.music

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource

/** Decorative when accompanied by the app name or track information. */
@Composable internal fun PulsoMark(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Icon(painterResource(R.drawable.ic_pulso_mark), contentDescription = null, modifier = modifier, tint = tint)
}
