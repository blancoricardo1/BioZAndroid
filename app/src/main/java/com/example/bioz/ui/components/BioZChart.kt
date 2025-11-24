package com.example.bioz.ui.components

import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.bioz.model.BioZPacket
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.utils.EntryXComparator
import java.util.Collections

@Composable
fun BioZChart(packets: List<BioZPacket>, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth().height(240.dp),
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                setTouchEnabled(true)
                setPinchZoom(true)
                legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
                legend.horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
            }
        },
        update = { chart ->
            val qEntries = packets.mapIndexed { index, packet -> Entry(index.toFloat(), packet.q) }
            val iEntries = packets.mapIndexed { index, packet -> Entry(index.toFloat(), packet.i) }
            Collections.sort(qEntries, EntryXComparator())
            Collections.sort(iEntries, EntryXComparator())

            val qSet = LineDataSet(qEntries, "Q").apply {
                color = Color.BLUE
                setDrawCircles(false)
                lineWidth = 2f
            }
            val iSet = LineDataSet(iEntries, "I").apply {
                color = Color.RED
                setDrawCircles(false)
                lineWidth = 2f
            }

            chart.data = LineData(qSet, iSet)
            chart.invalidate()
        }
    )
}
