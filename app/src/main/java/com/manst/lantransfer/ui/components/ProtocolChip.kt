package com.manst.lantransfer.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manst.lantransfer.core.TransferProtocol

/**
 * 协议标签：四种协议使用不同颜色，深浅色主题分别给出一套容器色/内容色。
 */
@Composable
fun ProtocolChip(protocol: TransferProtocol, modifier: Modifier = Modifier) {
    val darkTheme = isSystemInDarkTheme()
    val (container, content) = protocolColors(protocol, darkTheme)
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier
    ) {
        Text(
            text = protocol.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

private fun protocolColors(protocol: TransferProtocol, darkTheme: Boolean): Pair<Color, Color> = when (protocol) {
    TransferProtocol.FTP ->
        if (darkTheme) Color(0xFF16324E) to Color(0xFF9FC9F0)
        else Color(0xFFDCEBFB) to Color(0xFF0F4C81)

    TransferProtocol.FTPS ->
        if (darkTheme) Color(0xFF0D3B33) to Color(0xFF8FE0CE)
        else Color(0xFFD3EFE9) to Color(0xFF0E7C6B)

    TransferProtocol.SFTP ->
        if (darkTheme) Color(0xFF3D2A0C) to Color(0xFFFFC978)
        else Color(0xFFFFEFD2) to Color(0xFF8A5200)

    TransferProtocol.SCP ->
        if (darkTheme) Color(0xFF32234D) to Color(0xFFD3BDF7)
        else Color(0xFFEDE2FB) to Color(0xFF5B3C99)
}
