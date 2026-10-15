@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.photocull

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlin.math.max

private val Indigo = Color(0xFF3F51B5)
private val Red = Color(0xFFD32F2F)
private val Green = Color(0xFF2E7D32)

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.load(it) }
    }
    var preview by remember { mutableStateOf<Photo?>(null) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text("فرز الصور") },
                actions = {
                    TextButton(onClick = { picker.launch(null) }) {
                        Text(if (vm.tree == null) "اختار فولدر" else "غيّر الفولدر")
                    }
                }
            )
        },
        bottomBar = { if (vm.selected.isNotEmpty()) SelectionBar(vm) }
    ) { pad ->
        if (vm.tree == null) Welcome(pad) { picker.launch(null) }
        else Content(vm, pad) { preview = it }
    }

    preview?.let { p -> PreviewDialog(vm, p) { preview = null } }
}

@Composable
private fun Welcome(pad: PaddingValues, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(pad).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("فرز الصور للمصورين", fontSize = 24.sp, textAlign = TextAlign.Center)
        Text(
            "اختار فولدر الصور والتطبيق يطلّعلك الصور المهزوزة، اللي فيها عين مقفولة، " +
                "الغامقة أو الفاتحة زيادة، واللقطات المكررة مع اختيار أفضل لقطة. " +
                "كل التحليل على الموبايل نفسه ومفيش حاجة بتترفع على نت.",
            modifier = Modifier.padding(vertical = 16.dp),
            textAlign = TextAlign.Center
        )
        Button(onClick = onPick) { Text("اختار فولدر الصور") }
    }
}

@Composable
private fun Content(vm: MainViewModel, pad: PaddingValues, onPreview: (Photo) -> Unit) {
    val visible = vm.photos.filter { vm.matches(it, vm.filter) }
    Column(Modifier.padding(pad)) {
        if (vm.busy) {
            LinearProgressIndicator(
                progress = vm.done / max(1, vm.photos.size).toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "بيحلل ${vm.done} / ${vm.photos.size}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                fontSize = 12.sp
            )
        }
        Column(Modifier.padding(horizontal = 12.dp)) {
            Text("حدّ الهزّة: ${vm.blurLimit.toInt()}  (أكبر = يعتبر صور أكتر مهزوزة)", fontSize = 12.sp)
            Slider(
                value = vm.blurLimit,
                onValueChange = { vm.blurLimit = it },
                valueRange = 10f..400f
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(Filter.values().toList()) { f ->
                FilterChip(
                    selected = vm.filter == f,
                    onClick = { vm.filter = f },
                    label = { Text("${f.label} (${vm.count(f)})") }
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("المعروض: ${visible.size}", fontSize = 13.sp)
            TextButton(onClick = { vm.selectVisible() }) { Text("حدد الكل المعروض") }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(visible, key = { it.uri.toString() }) { p -> Cell(vm, p, onPreview) }
        }
    }
}

@Composable
private fun Cell(vm: MainViewModel, p: Photo, onPreview: (Photo) -> Unit) {
    val sel = p.uri in vm.selected
    val flags = vm.flags(p.uri)
    val a = vm.results[p.uri]
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(onClick = { vm.toggle(p.uri) }, onLongClick = { onPreview(p) })
    ) {
        AsyncImage(
            model = p.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (a == null && vm.busy) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(20.dp), strokeWidth = 2.dp)
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            flags.forEach { Badge(it.label, Red) }
            if (a != null && a.group >= 0 && a.bestInGroup) Badge("أفضل لقطة", Green)
        }
        if (sel) {
            Box(Modifier.fillMaxSize().background(Color(0x663F51B5)).border(3.dp, Indigo)) {
                Icon(
                    Icons.Default.Check, contentDescription = null, tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, color = Color.White, fontSize = 10.sp,
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
private fun SelectionBar(vm: MainViewModel) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("محدد: ${vm.selected.size}")
            Row {
                TextButton(onClick = { vm.clearSelection() }) { Text("إلغاء") }
                Button(onClick = { vm.moveSelected() }, enabled = !vm.busy) { Text("انقل للمرفوضات") }
            }
        }
    }
}

@Composable
private fun PreviewDialog(vm: MainViewModel, p: Photo, onClose: () -> Unit) {
    val a = vm.results[p.uri]
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                AsyncImage(
                    model = p.uri, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                )
                Text(p.name, modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp)
                if (a != null) {
                    Text("الحدة: ${a.sharpness.toInt()}  |  الإضاءة: ${a.brightness.toInt()}", fontSize = 13.sp)
                    Text("وجوه: ${a.faces}  |  عيون مقفولة: ${a.closedFaces}", fontSize = 13.sp)
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("قفل") }
            }
        }
    }
}
