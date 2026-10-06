package dev.qi.torsionbalance.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.qi.torsionbalance.MainViewModel
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val experiments by viewModel.experiments.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.refreshExperiments() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Experiments") },
                navigationIcon = { Button(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        if (experiments.isEmpty()) {
            Text(
                "No experiments yet. Record one from the main screen.",
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(experiments, key = { it.absolutePath }) { exp ->
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        .format(Date(exp.createdAtMs))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    ) {
                        Text(exp.fileName)
                        Text("$date · ${exp.sampleCount} samples · ${formatElapsed(exp.durationMs)}")
                        Row {
                            Button(
                                onClick = {
                                    val csv = java.io.File(exp.absolutePath)
                                    val sidecar = java.io.File(
                                        csv.parentFile,
                                        csv.nameWithoutExtension + ".trigger.json",
                                    )
                                    val authority = "${context.packageName}.fileprovider"
                                    if (sidecar.exists()) {
                                        val uris = ArrayList(
                                            listOf(csv, sidecar).map {
                                                FileProvider.getUriForFile(context, authority, it)
                                            },
                                        )
                                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                            type = "*/*"
                                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(intent, "Share CSV + trigger summary"),
                                        )
                                    } else {
                                        val uri = FileProvider.getUriForFile(context, authority, csv)
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/csv"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(intent, "Share CSV"),
                                        )
                                    }
                                },
                            ) { Text("Share") }
                            Button(
                                onClick = { viewModel.deleteExperiment(exp.absolutePath) },
                                modifier = Modifier.padding(start = 8.dp),
                            ) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
