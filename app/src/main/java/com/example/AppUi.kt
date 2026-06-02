package com.example

import android.Manifest
import android.content.Context
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ShareViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // State collection
    val deviceName by viewModel.deviceNameState.collectAsStateWithLifecycle()
    val localIp by viewModel.localIpState.collectAsStateWithLifecycle()
    val peers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val transferState by viewModel.transferState.collectAsStateWithLifecycle()
    
    val apps by viewModel.installedApps.collectAsStateWithLifecycle()
    val gallery by viewModel.galleryItems.collectAsStateWithLifecycle()
    val audios by viewModel.audioItems.collectAsStateWithLifecycle()
    val isLoadingCategory by viewModel.isLoadingCategory.collectAsStateWithLifecycle()

    // Advanced multi-tab flows
    val currentExplorerDir by viewModel.currentExplorerDir.collectAsStateWithLifecycle()
    val explorerFiles by viewModel.explorerFiles.collectAsStateWithLifecycle()
    val selectedExplorerFiles by viewModel.selectedExplorerFiles.collectAsStateWithLifecycle()
    val totalStorageBytes by viewModel.totalStorageBytes.collectAsStateWithLifecycle()
    val freeStorageBytes by viewModel.freeStorageBytes.collectAsStateWithLifecycle()
    val transferHistory by viewModel.transferHistory.collectAsStateWithLifecycle()
    val recentPeers by viewModel.recentPeers.collectAsStateWithLifecycle()

    // Screen navigation/sheets local states
    val isSettingsOpen = remember { mutableStateOf(false) }
    val isQrSheetOpen = remember { mutableStateOf(false) }
    val selectedPeer = remember { mutableStateOf<Peer?>(null) }
    val activeCategoryTab = remember { mutableStateOf<String?>(null) } // "apps", "gallery", "audio", "clipboard"
    val clipboardText = remember { mutableStateOf("") }
    
    // Multi-tab layout (0: Aktarım, 1: Klasörler, 2: Geçmiş)
    val currentBottomTab = remember { mutableStateOf(0) }
    
    // Manual pairings input states
    val directIpText = remember { mutableStateOf("") }
    val isDirectConnectingRowOpen = remember { mutableStateOf(false) }
    val isPeerSelectorOpenForExplorer = remember { mutableStateOf(false) }
    val isSplashFinished = remember { mutableStateOf(false) }

    val profileImageUri by viewModel.profileImageUriState.collectAsStateWithLifecycle()

    val profilePicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val success = viewModel.setProfileImageFromUri(uri)
            if (success) {
                Toast.makeText(context, "Profil fotoğrafı güncellendi", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Hata: Fotoğraf ayarlanamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Permission request launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val cameraGranted = perms[Manifest.permission.CAMERA] ?: false
        if (!cameraGranted) {
            Toast.makeText(context, "Kamera izni QR tarama için gereklidir.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.resolveLocalInfo()
        viewModel.updateStorageMetrics()
        viewModel.initExplorer()
        
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.READ_MEDIA_IMAGES)
            needed.add(Manifest.permission.READ_MEDIA_VIDEO)
            needed.add(Manifest.permission.READ_MEDIA_AUDIO)
            needed.add("android.permission.POST_NOTIFICATIONS")
        } else {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        needed.add(Manifest.permission.CAMERA)
        permissionsLauncher.launch(needed.toTypedArray())
    }

    // System File Picker Launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty() && selectedPeer.value != null) {
            scope.launch {
                uris.forEach { u ->
                    viewModel.sendFileUri(selectedPeer.value!!, u)
                }
            }
        } else if (uris.isNotEmpty() && peers.isNotEmpty()) {
            // Pick first available peer if none selected
            val firstPeer = peers.values.first()
            selectedPeer.value = firstPeer
            uris.forEach { u ->
                viewModel.sendFileUri(firstPeer, u)
            }
        } else if (uris.isNotEmpty()) {
            Toast.makeText(context, "Lütfen önce bir hedef cihaz seçin veya QR taratın.", Toast.LENGTH_LONG).show()
        }
    }

    if (!isSplashFinished.value) {
        SplashScreen(onAnimationFinished = { isSplashFinished.value = true })
    } else {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("main_screen"),
            topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Logo",
                            tint = CyberCyan,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "VeloShare",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Slate50
                        )
                    }
                },
                actions = {
                    if (currentBottomTab.value == 1) {
                        IconButton(onClick = { viewModel.initExplorer() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Yenile", tint = Slate50)
                        }
                    }
                    IconButton(
                        onClick = { isQrSheetOpen.value = true },
                        modifier = Modifier.testTag("qr_button")
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = "QR Eşle", tint = Slate50)
                    }
                    IconButton(
                        onClick = { isSettingsOpen.value = true },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Ayarlar", tint = Slate50)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Slate900,
                    titleContentColor = Slate50
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Slate800,
                contentColor = CyberIndigo,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentBottomTab.value == 0,
                    onClick = { currentBottomTab.value = 0 },
                    label = { Text("Aktarım Merkezi", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Filled.Share, contentDescription = "Aktarım") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        indicatorColor = CyberIndigo.copy(alpha = 0.2f),
                        unselectedIconColor = Slate600,
                        unselectedTextColor = Slate600
                    )
                )
                NavigationBarItem(
                    selected = currentBottomTab.value == 1,
                    onClick = { currentBottomTab.value = 1 },
                    label = { Text("Klasörler", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Filled.FolderOpen, contentDescription = "Klasörler") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        indicatorColor = CyberIndigo.copy(alpha = 0.2f),
                        unselectedIconColor = Slate600,
                        unselectedTextColor = Slate600
                    )
                )
                NavigationBarItem(
                    selected = currentBottomTab.value == 2,
                    onClick = { currentBottomTab.value = 2 },
                    label = { Text("Geçmiş", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Filled.History, contentDescription = "Geçmiş") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        indicatorColor = CyberIndigo.copy(alpha = 0.2f),
                        unselectedIconColor = Slate600,
                        unselectedTextColor = Slate600
                    )
                )
            }
        },
        containerColor = Slate900
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentBottomTab.value) {
                0 -> { // TAB 0: AKTARIM MERKEZİ (RADAR, KATOGORİLER VB)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 1. KULLANICI PROFİLİ VE PORTATİF RADAR PANELİ
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Slate700, RoundedCornerShape(20.dp)),
                            colors = CardDefaults.cardColors(containerColor = Slate800),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "CİHAZ PROFİLİNİZ",
                                        color = Slate600,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = deviceName,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Slate50,
                                        fontSize = 18.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Wifi,
                                            contentDescription = null,
                                            tint = Slate600,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = "IP: $localIp",
                                            fontSize = 11.sp,
                                            color = Slate600,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(Slate900, RoundedCornerShape(8.dp))
                                                .border(0.5.dp, Slate700, RoundedCornerShape(8.dp))
                                                .clickable { isSettingsOpen.value = true }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Edit,
                                                    contentDescription = "Düzenle",
                                                    tint = Slate50,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text("Yeniden Adlandır", color = Slate50, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White)
                                            )
                                            Text("Keşif Aktif", color = Slate50, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                                
                                // Compact, sleek radar visualizer
                                Box(
                                    modifier = Modifier
                                        .size(94.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    RadarPulseAnimation()
                                    ProfileAvatar(
                                        imageUri = profileImageUri,
                                        deviceName = deviceName,
                                        size = 44.dp,
                                        onClick = { profilePicLauncher.launch("image/*") }
                                    )
                                }
                            }
                        }

                        // 2. YAKINDAKİ CİHAZLAR BAĞLANTI SEKMESİ
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Wifi, contentDescription = "Ağ", tint = Slate50, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "YAKINDAKİ AKTİF CİHAZLAR",
                                    fontWeight = FontWeight.Bold,
                                    color = Slate50,
                                    fontSize = 11.sp,
                                    letterSpacing = 1.sp
                                )
                            }
                            if (peers.isNotEmpty()) {
                                Text(
                                    text = "${peers.size} Cihaz Bulundu",
                                    color = Slate600,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        if (peers.isEmpty()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Slate700.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                                colors = CardDefaults.cardColors(containerColor = Slate900),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Slate50,
                                        strokeWidth = 2.dp
                                    )
                                    Column {
                                        Text(
                                            "Ağınız taranıyor...",
                                            fontWeight = FontWeight.Bold,
                                            color = Slate50,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            "Diğer cihazda da VeloShare açık ve aynı ağda olmalıdır.",
                                            color = Slate600,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            val isOfflineGuideOpen = remember { mutableStateOf(false) }
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Slate700.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                                    .clickable { isOfflineGuideOpen.value = !isOfflineGuideOpen.value },
                                colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Filled.CloudOff,
                                                contentDescription = "Offline Mode",
                                                tint = Slate50,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "İnternetsiz Çevrimdışı Aktarım Rehberi",
                                                fontWeight = FontWeight.Bold,
                                                color = Slate50,
                                                fontSize = 12.sp
                                            )
                                        }
                                        Icon(
                                            imageVector = if (isOfflineGuideOpen.value) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                            contentDescription = "Toggle Guide",
                                            tint = Slate600,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    
                                    AnimatedVisibility(
                                        visible = isOfflineGuideOpen.value,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        Column(modifier = Modifier.padding(top = 12.dp)) {
                                            HorizontalDivider(color = Slate700.copy(alpha = 0.3f), modifier = Modifier.padding(bottom = 12.dp))
                                            
                                            OfflineGuideStep(
                                                stepNumber = "1",
                                                title = "Kişisel Erişim Noktası Açın",
                                                description = "Gönderici veya alıcı cihazlardan birinde Mobil Etkin Noktayı (Hotspot) açın. Hücresel mobil verinizin açık olmasına gerek yoktur; sadece yerel bir kablosuz ağ kurulur."
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            OfflineGuideStep(
                                                stepNumber = "2",
                                                title = "Diğer Cihazı Wi-Fi ile Bağlayın",
                                                description = "Diğer cihazı, az önce açtığınız bu Wi-Fi ağına bağlayın."
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            OfflineGuideStep(
                                                stepNumber = "3",
                                                title = "VeloShare Uygulamasını Açın",
                                                description = "Her iki cihazda da VeloShare uygulamasını başlatın."
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            OfflineGuideStep(
                                                stepNumber = "4",
                                                title = "QR Kod veya Manuel IP ile Eşleyin",
                                                description = "Yukarıdaki 'QR Kod Eşle' ikonuna tıklayıp QR kodu taratın ya da alttaki 'Manuel IP' kısmından alıcının IP adresini girerek anında aktarımı başlatın."
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(peers.values.toList()) { peer ->
                                    PeerCircleItem(
                                        peer = peer,
                                        isSelected = selectedPeer.value?.id == peer.id,
                                        onClick = {
                                            selectedPeer.value = if (selectedPeer.value?.id == peer.id) null else peer
                                        }
                                    )
                                }
                            }
                        }

                        // 3. EĞER BİR CİHAZ SEÇİLİYSE YUKARI ÇIKAN HUD BİLGİ KARTI
                        selectedPeer.value?.let { peer ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.5.dp, Slate50, RoundedCornerShape(16.dp)),
                                colors = CardDefaults.cardColors(containerColor = Slate800),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Slate900)
                                                .border(1.dp, Slate700, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (peer.os == "Android") Icons.Filled.Android else Icons.Filled.Laptop,
                                                contentDescription = null,
                                                tint = Slate50,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = "HEDEF GÖNDERİM ALICISI SEÇİLDİ",
                                                color = Slate600,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.5.sp
                                            )
                                            Text(
                                                text = peer.name,
                                                fontWeight = FontWeight.Bold,
                                                color = Slate50,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { selectedPeer.value = null }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Seçimi Kaldır",
                                            tint = CoralWarm,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 4. MANUEL IP DOĞRUDAN BAĞLANTI & GEÇMİŞ PANELİ (COLLAPSIBLE SLEEK CARD)
                        Spacer(modifier = Modifier.height(14.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Slate700.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = if (isDirectConnectingRowOpen.value) Slate800 else Color.Transparent),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isDirectConnectingRowOpen.value = !isDirectConnectingRowOpen.value },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.Link,
                                            contentDescription = null,
                                            tint = Slate50,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                "Manuel IP & Bağlantı Geçmişi",
                                                fontWeight = FontWeight.Bold,
                                                color = Slate50,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                "IP adresi yazarak doğrudan transfer başlatın",
                                                color = Slate600,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                    Icon(
                                        imageVector = if (isDirectConnectingRowOpen.value) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Slate600,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                
                                if (isDirectConnectingRowOpen.value) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Divider(color = Slate700, thickness = 0.8.dp)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    Text(
                                        "Hedef Cihazın IP Adresi",
                                        fontWeight = FontWeight.SemiBold,
                                        color = Slate600,
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        OutlinedTextField(
                                            value = directIpText.value,
                                            onValueChange = { directIpText.value = it },
                                            placeholder = { Text("Örn: 192.168.1.50", color = Slate600, fontSize = 12.sp) },
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = Slate50,
                                                unfocusedTextColor = Slate50,
                                                focusedBorderColor = Slate50,
                                                unfocusedBorderColor = Slate700,
                                                focusedContainerColor = Color.Transparent,
                                                unfocusedContainerColor = Color.Transparent
                                            ),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        Button(
                                            onClick = {
                                                if (directIpText.value.isNotBlank()) {
                                                    val ip = directIpText.value.trim()
                                                    viewModel.connectDirectlyAndSend(ip, TransferEngine.SERVER_PORT, null, null)
                                                    Toast.makeText(context, "Doğrudan IP adresi eşlendi: $ip", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Lütfen geçerli bir IP adresi yazın.", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Slate50,
                                                contentColor = Slate900
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.height(50.dp)
                                        ) {
                                            Text("Bağlan", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                    
                                    // IP Geçmişi
                                    if (recentPeers.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            "Son Bağlanılan Cihazlar",
                                            fontWeight = FontWeight.SemiBold,
                                            color = Slate600,
                                            fontSize = 11.sp
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            items(recentPeers) { rp ->
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(Slate900)
                                                        .border(0.5.dp, Slate700, RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            directIpText.value = rp.ip
                                                            selectedPeer.value = rp
                                                            Toast.makeText(context, "${rp.name} IP adresi seçildi.", Toast.LENGTH_SHORT).show()
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Devices,
                                                            contentDescription = null,
                                                            tint = Slate50,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Column {
                                                            Text(rp.name, fontWeight = FontWeight.Bold, color = Slate50, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                            Text(rp.ip, color = Slate600, fontSize = 8.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 5. DOSYA PAYLAŞIM VE GÖNDERİM MERKEZİ (KATEGORİ GRUPLARI)
                        Spacer(modifier = Modifier.height(22.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = "Paylaşım", tint = Slate50, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "HIZLI DOSYA PAYLAŞIMI",
                                fontWeight = FontWeight.Bold,
                                color = Slate50,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CategorySmallCard(
                                title = "Uygulamalar",
                                subtitle = "Yüklü APK kopyaları",
                                icon = Icons.Outlined.Android,
                                color = Slate50,
                                modifier = Modifier.weight(1f)
                            ) {
                                viewModel.loadInstalledApps()
                                activeCategoryTab.value = "apps"
                            }
                            CategorySmallCard(
                                title = "Galeri",
                                subtitle = "Fotoğraflar & Videolar",
                                icon = Icons.Outlined.Image,
                                color = Slate50,
                                modifier = Modifier.weight(1f)
                            ) {
                                viewModel.loadGalleryItems()
                                activeCategoryTab.value = "gallery"
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CategorySmallCard(
                                title = "Müzikler",
                                subtitle = "Şarkılar & Ses Dosyaları",
                                icon = Icons.Outlined.MusicNote,
                                color = Slate50,
                                modifier = Modifier.weight(1f)
                            ) {
                                viewModel.loadAudioItems()
                                activeCategoryTab.value = "audio"
                            }
                            CategorySmallCard(
                                title = "Pano Al/Ver",
                                subtitle = "Kopyalanan Metinler",
                                icon = Icons.Outlined.ContentPaste,
                                color = Slate50,
                                modifier = Modifier.weight(1f)
                            ) {
                                activeCategoryTab.value = "clipboard"
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Unified High-End Sistem Dosya Seçme CTA
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("pick_any_file")
                                .border(1.dp, Slate700, RoundedCornerShape(12.dp)),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Slate900,
                                contentColor = Slate50
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = "Dosya Seç", tint = Slate50, modifier = Modifier.size(18.dp))
                                Text("Tüm Dosya Türlerini Tarat & Gönder", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
                
                1 -> { // TAB 1: SİSTEM DEEP EXPLORER (NATIVE CHOOSE EVERYTHING ACCORDING TO USER REQ)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // STORAGE SPACE DETAIL ANALYTICS CHART BLOCK
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .border(1.dp, Slate700.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Dahili Hafıza Detayları", fontWeight = FontWeight.Bold, color = Slate50, fontSize = 13.sp)
                                    val freeFormatted = viewModel.formatFileSize(freeStorageBytes)
                                    val totalFormatted = viewModel.formatFileSize(totalStorageBytes)
                                    Text("Boş Alan: $freeFormatted / Toplam: $totalFormatted", color = Slate600, fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                val usedRatio = if (totalStorageBytes > 0) {
                                    (totalStorageBytes - freeStorageBytes).toFloat() / totalStorageBytes.toFloat()
                                } else 0.5f
                                
                                // Beautiful dynamic linear storage bar indicator
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(CircleShape)
                                        .background(Slate700)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(usedRatio)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.horizontalGradient(
                                                    colors = listOf(CyberIndigo, CyberCyan)
                                                )
                                            )
                                    )
                                }
                            }
                        }

                        // CURRENT PATH BREADCRUMBS
                        val currentPath = currentExplorerDir?.absolutePath ?: "Depolama"
                        val docFriendlyPath = if (currentPath.contains("emulated/0")) {
                            "Dahili Hafıza" + currentPath.substringAfter("emulated/0")
                        } else {
                            currentPath
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (currentExplorerDir?.parentFile != null && currentExplorerDir?.absolutePath != android.os.Environment.getExternalStorageDirectory()?.absolutePath) {
                                IconButton(onClick = { viewModel.navigateUp() }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "Geri", tint = CyberCyan, modifier = Modifier.size(20.dp))
                                }
                            }
                            Text(
                                text = docFriendlyPath,
                                color = Slate50,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // FOLDER DIRECTORY ITEMS RENDERING
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (explorerFiles.isEmpty()) {
                                EmptyStatePlaceholder("Bu klasör boş veya erişim izni yok.")
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(explorerFiles) { file ->
                                        val isSelected = selectedExplorerFiles.contains(file)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    if (file.isDirectory) {
                                                        viewModel.navigateToDir(file)
                                                    } else {
                                                        viewModel.toggleSelectFile(file)
                                                    }
                                                }
                                                .background(if (isSelected) CyberIndigo.copy(alpha = 0.15f) else Color.Transparent)
                                                .padding(vertical = 10.dp, horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val icon = if (file.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile
                                            val iconColor = if (file.isDirectory) CyberCyan else Slate50
                                            
                                            Box(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (file.isDirectory) CyberCyan.copy(alpha = 0.1f) else Slate800),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
                                            }

                                            Spacer(modifier = Modifier.width(14.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    file.name,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Slate50,
                                                    fontSize = 14.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    if (file.isDirectory) "Klasör" else viewModel.formatFileSize(file.length()),
                                                    color = Slate600,
                                                    fontSize = 11.sp
                                                )
                                            }

                                            if (!file.isDirectory) {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = { viewModel.toggleSelectFile(file) },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = CyberIndigo,
                                                        uncheckedColor = Slate700,
                                                        checkmarkColor = Slate50
                                                    )
                                                )
                                            } else {
                                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Slate700, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        Divider(color = Slate800.copy(alpha = 0.6f), thickness = 0.8.dp)
                                    }
                                }
                            }
                        }

                        // FLOATING SELECTED QUEUE ROW SHEET
                        AnimatedVisibility(
                            visible = selectedExplorerFiles.isNotEmpty(),
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp)
                                    .border(1.3.dp, CyberCyan, RoundedCornerShape(16.dp)),
                                colors = CardDefaults.cardColors(containerColor = Slate800),
                                shape = RoundedCornerShape(16.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("${selectedExplorerFiles.size} Dosya Seçildi", fontWeight = FontWeight.Bold, color = Slate50, fontSize = 14.sp)
                                        Text("Kuyruk ile eşleşme modu hazır.", color = Slate600, fontSize = 11.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { viewModel.clearSelectedFiles() }) {
                                            Text("Temizle", color = CoralWarm, fontWeight = FontWeight.SemiBold)
                                        }
                                        Button(
                                            onClick = { isPeerSelectorOpenForExplorer.value = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Seçilenleri Gönder", color = Slate900, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                2 -> { // TAB 2: TRANSFER GEÇMİŞİ (SYSTEM LOG)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Tamamlanan Aktarımlar", fontWeight = FontWeight.Bold, color = Slate50, fontSize = 15.sp)
                            if (transferHistory.isNotEmpty()) {
                                TextButton(onClick = { viewModel.transferHistory.value = emptyList() }) {
                                    Text("Temizle", color = Slate600)
                                }
                            }
                        }

                        if (transferHistory.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.History, contentDescription = null, tint = Slate800, modifier = Modifier.size(50.dp))
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text("Henüz herhangi bir dosya paylaşmadınız.", color = Slate600, fontSize = 13.sp)
                                    Text("Gönderilen / alınan dosyalar burada kayıt altına alınır.", color = Slate600, fontSize = 10.sp)
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(transferHistory) { item ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, Slate800, RoundedCornerShape(14.dp)),
                                        colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.5f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clip(CircleShape)
                                                        .background(if (item.isIncoming) CyberCyan.copy(alpha = 0.1f) else CyberIndigo.copy(alpha = 0.1f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = if (item.isIncoming) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                                                        contentDescription = null,
                                                        tint = if (item.isIncoming) CyberCyan else CyberIndigo,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(item.name, color = Slate50, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text("Cihaz: ${item.peerName}", color = Slate600, fontSize = 10.sp)
                                                }
                                            }
                                            
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (item.isSuccess) MintNeon.copy(alpha = 0.15f) else CoralWarm.copy(alpha = 0.15f),
                                                        RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = if (item.isSuccess) "Başarılı" else "Hata",
                                                    color = if (item.isSuccess) MintNeon else CoralWarm,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // KATEGORİ DETAY ALANI (Slide up or expanding view)
            AnimatedVisibility(
                visible = activeCategoryTab.value != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Slate900)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Kategori Başlığı
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Slate800)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { activeCategoryTab.value = null }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "Geri", tint = Slate50)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when (activeCategoryTab.value) {
                                        "apps" -> "Uygulamalar (APK)"
                                        "gallery" -> "Medya Galerisi"
                                        "audio" -> "Müzikler & Ses"
                                        "clipboard" -> "Pano Gönderimi"
                                        else -> "Kategori"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    color = Slate50,
                                    fontSize = 18.sp
                                )
                            }
                            
                            if (isLoadingCategory) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = CyberCyan)
                            }
                        }

                        // İçindekiler listesi
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            when (activeCategoryTab.value) {
                                "apps" -> {
                                    if (apps.isEmpty() && !isLoadingCategory) {
                                        EmptyStatePlaceholder("Yüklü uygulama bulunamadı.")
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            items(apps) { app ->
                                                AppPackItem(app) {
                                                    val apkFileName = if (app.name.lowercase().endsWith(".apk")) app.name else "${app.name}.apk"
                                                    sendSelectedContent(context, scope, viewModel, peers, selectedPeer, app.uri, apkFileName, app.size)
                                                }
                                            }
                                        }
                                    }
                                }
                                "gallery" -> {
                                    if (gallery.isEmpty() && !isLoadingCategory) {
                                        EmptyStatePlaceholder("Galeride fotoğraf/video bulunamadı.")
                                    } else {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(3),
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            items(gallery) { item ->
                                                MediaGridItem(item) {
                                                    sendSelectedContent(context, scope, viewModel, peers, selectedPeer, item.uri, item.name, item.size)
                                                }
                                            }
                                        }
                                    }
                                }
                                "audio" -> {
                                    if (audios.isEmpty() && !isLoadingCategory) {
                                        EmptyStatePlaceholder("Cihazda müzik dosyası bulunamadı.")
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            items(audios) { item ->
                                                AudioLineItem(item) {
                                                    sendSelectedContent(context, scope, viewModel, peers, selectedPeer, item.uri, item.name, item.size)
                                                }
                                            }
                                        }
                                    }
                                }
                                "clipboard" -> {
                                    ClipboardPayloadEditor(
                                        text = clipboardText.value,
                                        onTextChanged = { clipboardText.value = it }
                                    ) {
                                        val text = clipboardText.value
                                        if (text.isBlank()) {
                                            Toast.makeText(context, "Lütfen gönderilecek metni yazın.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            if (selectedPeer.value != null) {
                                                viewModel.sendClipboardText(selectedPeer.value!!, text)
                                            } else if (peers.isNotEmpty()) {
                                                val firstPeer = peers.values.first()
                                                selectedPeer.value = firstPeer
                                                viewModel.sendClipboardText(firstPeer, text)
                                            } else {
                                                Toast.makeText(context, "Lütfen önce bir hedef cihaz seçin ya da doğrudan QR taratın.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // PEER CHOOSER POPUP FOR DIRECT NATIVE EXPLORER SEND
            if (isPeerSelectorOpenForExplorer.value) {
                Dialog(onDismissRequest = { isPeerSelectorOpenForExplorer.value = false }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Slate700, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Slate800)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Alıcı Cihazı Seçin",
                                fontWeight = FontWeight.Bold,
                                color = Slate50,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(bottom = 14.dp)
                            )

                            if (peers.isEmpty()) {
                                Text(
                                    "Hiç aktif cihaz bulunamadı. Lütfen karşı cihazın VeloShare uygulamasını açıp bekleyin veya Manuel IP eşleme kullanın.",
                                    color = Slate600,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(peers.values.toList()) { p ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Slate900.copy(alpha = 0.5f))
                                                .clickable {
                                                    viewModel.sendSelectedExplorerFiles(p)
                                                    isPeerSelectorOpenForExplorer.value = false
                                                    Toast.makeText(context, "Seçilen dosyalar kuyruğa eklendi!", Toast.LENGTH_SHORT).show()
                                                    currentBottomTab.value = 0 // jump back to monitor transfer
                                                }
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Filled.Devices, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(24.dp))
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(p.name, fontWeight = FontWeight.Bold, color = Slate50, fontSize = 13.sp)
                                                Text(p.ip, color = Slate600, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Button(
                                onClick = { isPeerSelectorOpenForExplorer.value = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Vazgeç", color = Slate50)
                            }
                        }
                    }
                }
            }

            // TRANSFER DURUM VE PROGRESS EKRANI (OVERLAYS)
            when (val state = transferState) {
                is TransferState.WaitingForAccept -> {
                    IncomingRequestDialog(
                        request = state,
                        onResponse = { approved ->
                            state.onAccept(approved)
                        }
                    )
                }
                is TransferState.Progress -> {
                    TransferProgressOverlay(
                        progress = state,
                        onCancel = {
                            viewModel.resetState()
                        }
                    )
                }
                is TransferState.Success -> {
                    TransferSuccessOverlay(
                        success = state,
                        onClose = {
                            viewModel.resetState()
                        }
                    )
                }
                is TransferState.Error -> {
                    TransferErrorDialog(
                        error = state,
                        onClose = {
                            viewModel.resetState()
                        }
                    )
                }
                else -> {}
            }

            // QR DRAWER & KAMERA SCANNED DIALOG
            if (isQrSheetOpen.value) {
                QrShareDialog(
                    myIp = localIp,
                    myPort = TransferEngine.SERVER_PORT,
                    onClose = { isQrSheetOpen.value = false },
                    onDirectConnectAndSend = { targetIp, targetPort ->
                        isQrSheetOpen.value = false
                        viewModel.connectDirectlyAndSend(targetIp, targetPort, null, null)
                    }
                )
            }

            // SETTINGS / RE-NAME DIALOG
            if (isSettingsOpen.value) {
                SettingsDialog(
                    currentName = deviceName,
                    onSave = { newName ->
                        viewModel.updateDeviceName(newName)
                        isSettingsOpen.value = false
                    },
                    onDismiss = { isSettingsOpen.value = false },
                    viewModel = viewModel
                )
            }
        }
    }
}
}

// Send helper selection
private fun sendSelectedContent(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    viewModel: ShareViewModel,
    peers: Map<String, Peer>,
    selectedPeer: MutableState<Peer?>,
    uri: Uri,
    name: String,
    size: Long
) {
    val peer = selectedPeer.value
    if (peer != null) {
        viewModel.sendFileUri(peer, uri, name, size)
    } else if (peers.isNotEmpty()) {
        val firstPeer = peers.values.first()
        selectedPeer.value = firstPeer
        viewModel.sendFileUri(firstPeer, uri, name, size)
    } else {
        Toast.makeText(context, "Göndermek için alıcı bir VeloShare cihazı (Peer) seçin.", Toast.LENGTH_LONG).show()
    }
}

// CUSTOM RADAR PULSING CANVAS
@Composable
fun RadarPulseAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    
    val pulse1 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 0, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "pulse1"
    )

    val pulse2 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "pulse2"
    )

    val pulse3 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "pulse3"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f

        // Circle 1
        drawCircle(
            color = CyberIndigo,
            radius = maxRadius * pulse1.value,
            center = center,
            alpha = 1f - pulse1.value,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
        )

        // Circle 2
        drawCircle(
            color = CyberCyan,
            radius = maxRadius * pulse2.value,
            center = center,
            alpha = 1f - pulse2.value,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
        )

        // Circle 3
        drawCircle(
            color = CyberIndigo,
            radius = maxRadius * pulse3.value,
            center = center,
            alpha = 1f - pulse3.value,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
        )
    }
}

// INDIVIDUAL PEER CHIPS IN HORIZONTAL ROW
@Composable
fun PeerCircleItem(
    peer: Peer,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(70.dp),
            contentAlignment = Alignment.Center
        ) {
            // Ripple background if selected
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(CyberIndigo.copy(alpha = 0.25f))
                        .border(2.dp, CyberIndigo, CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Slate800)
                        .border(1.dp, Slate700, CircleShape)
                )
            }

            // Initials
            val initials = if (peer.name.length >= 2) peer.name.take(2).uppercase() else peer.name.uppercase()
            Text(
                initials,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = if (isSelected) CyberCyan else Slate50
            )

            // OS Tiny Indicator Shield
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (peer.os == "Android") MintNeon else CyberCyan)
                    .align(Alignment.BottomEnd)
                    .border(1.5.dp, Slate900, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (peer.os == "Android") Icons.Filled.Android else Icons.Filled.Laptop,
                    contentDescription = null,
                    tint = Slate900,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = peer.name,
            fontWeight = FontWeight.SemiBold,
            color = Slate50,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(76.dp),
            textAlign = TextAlign.Center
        )
        Text(
            text = peer.ip,
            color = Slate600,
            fontSize = 10.sp
        )
    }
}

// CATEGORY ENTRY PANEL CARD
@Composable
fun CategorySmallCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(72.dp)
            .clickable(onClick = onClick)
            .border(1.dp, Slate700.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, color = Slate50, fontSize = 13.sp)
                Text(subtitle, color = Slate600, fontSize = 10.sp)
            }
        }
    }
}

// APP ICON RENDER WITH CACHE
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pm = context.packageManager
    var appIconDrawable by remember(packageName) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    
    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                appIconDrawable = pm.getApplicationIcon(packageName)
            } catch (e: Exception) {
                // fallback
            }
        }
    }
    
    if (appIconDrawable != null) {
        val bitmap = remember(appIconDrawable) {
            val drawable = appIconDrawable!!
            val bmp = android.graphics.Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier
        )
    } else {
        Icon(
            Icons.Outlined.Android,
            contentDescription = null,
            tint = MintNeon,
            modifier = modifier
        )
    }
}

// AUDIO METADATA ALBUM COVER RENDER
@Composable
fun AudioAlbumItem(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var albumArtBytes by remember(item.uri) { mutableStateOf<ByteArray?>(null) }
    var loaded by remember(item.uri) { mutableStateOf(false) }
    
    LaunchedEffect(item.uri) {
        withContext(Dispatchers.IO) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        val thumbnail = context.contentResolver.loadThumbnail(item.uri, android.util.Size(120, 120), null)
                        val stream = java.io.ByteArrayOutputStream()
                        thumbnail.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, stream)
                        albumArtBytes = stream.toByteArray()
                        loaded = true
                    } catch (e: Exception) {
                        // fallback
                    }
                }
                if (!loaded) {
                    val mmr = android.media.MediaMetadataRetriever()
                    mmr.setDataSource(context, item.uri)
                    albumArtBytes = mmr.embeddedPicture
                    mmr.release()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    if (albumArtBytes != null) {
        val bitmap = remember(albumArtBytes) {
            val bytes = albumArtBytes!!
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier
            )
        } else {
            DefaultAudioIcon(modifier)
        }
    } else {
        DefaultAudioIcon(modifier)
    }
}

@Composable
fun DefaultAudioIcon(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Slate700.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.MusicNote,
            contentDescription = "Audio",
            tint = CyberCyan,
            modifier = Modifier.size(20.dp)
        )
    }
}

// APK LIST ITEM LAYOUT
@Composable
fun AppPackItem(app: SystemAppInfo, onSendClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Slate700.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(
                    packageName = app.packageName,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(app.name, fontWeight = FontWeight.Bold, color = Slate50, fontSize = 14.sp)
                    Text("${app.packageName} • ${app.sizeFormatted}", color = Slate600, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Button(
                onClick = onSendClick,
                colors = ButtonDefaults.buttonColors(containerColor = CyberIndigo),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Gönder", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// MEDIA MINI THUMBNAIL GRID CARDS
@Composable
fun MediaGridItem(item: MediaItem, onSendClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Slate800)
            .border(1.dp, Slate700.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onSendClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.uri)
                .crossfade(true)
                .build(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (item.mimeType.startsWith("video")) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = "Video",
                    tint = CyberCyan.copy(alpha = 0.8f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // Title and size details overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(4.dp)
                .align(Alignment.BottomCenter)
        ) {
            Column {
                Text(
                    item.name,
                    color = Slate50,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(item.sizeFormatted, color = Slate600, fontSize = 8.sp)
            }
        }
    }
}

// AUDIO CHANNELS LIST ITEM
@Composable
fun AudioLineItem(item: MediaItem, onSendClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Slate700.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                AudioAlbumItem(
                    item = item,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(item.name, fontWeight = FontWeight.Bold, color = Slate50, fontSize = 14.sp)
                    Text(item.sizeFormatted, color = Slate600, fontSize = 11.sp)
                }
            }
            Button(
                onClick = onSendClick,
                colors = ButtonDefaults.buttonColors(containerColor = CyberIndigo),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Gönder", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// CLIPBOARD TEXT VIEW AND COMPOSER CARDS
@Composable
fun ClipboardPayloadEditor(
    text: String,
    onTextChanged: (String) -> Unit,
    onSendClick: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Read local clipboard automatically first
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val pasted = clipData.getItemAt(0).text.toString()
                    onTextChanged(pasted)
                    Toast.makeText(context, "Panodaki metin yapıştırıldı!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Panonuz boş.", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Slate700, RoundedCornerShape(12.dp))
                .background(Slate800.copy(alpha = 0.5f))
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ContentPaste, contentDescription = "Paste", tint = CoralWarm)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cihaz Panosundan Otomatik Çek", color = Slate50, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("clipboard_editor"),
            placeholder = { Text("Ağdaki cihazlara göndermek istediğiniz mesajı, şifreyi, veya web linkini buraya yazın...", color = Slate600) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Slate50,
                unfocusedTextColor = Slate50,
                focusedBorderColor = CyberIndigo,
                unfocusedBorderColor = Slate700,
                focusedContainerColor = Slate800.copy(alpha = 0.3f),
                unfocusedContainerColor = Slate800.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                keyboardController?.hide()
                onSendClick()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("send_clipboard_btn"),
            colors = ButtonDefaults.buttonColors(containerColor = CoralWarm),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Filled.Send, contentDescription = "Send")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Pano Metnini Paylaş", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

// INCOMING CONNECTION ACCEPT/REJECT PORTAL
@Composable
fun IncomingRequestDialog(
    request: TransferState.WaitingForAccept,
    onResponse: (Boolean) -> Unit
) {
    Dialog(onDismissRequest = { onResponse(false) }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Slate700, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Slate800)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Glow Indicator Icon
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(CyberIndigo.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (request.type == "clipboard") Icons.Filled.ContentPaste else Icons.Filled.FileDownload,
                        contentDescription = null,
                        tint = CyberIndigo,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "${request.peerName} Şunu Paylaşıyor:",
                    color = Slate600,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = request.name,
                    color = Slate50,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (request.type != "clipboard") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Boyut: " + formatBytes(request.size),
                        color = CyberCyan,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { onResponse(false) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("reject_request"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralWarm),
                        border = BorderStroke(1.dp, CoralWarm.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Yadsı/Reddet", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onResponse(true) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("accept_request"),
                        colors = ButtonDefaults.buttonColors(containerColor = MintNeon),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Onayla & Al", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// RUNNING TRANSFER DATA RATES & PROGRESS OVERLAY
@Composable
fun TransferProgressOverlay(
    progress: TransferState.Progress,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = {}) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Slate700, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Slate800)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (progress.isSending) "Dosya Gönderiliyor..." else "Dosya Alınıyor...",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Slate50
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Aygıt: ${progress.peerName}",
                    fontSize = 12.sp,
                    color = Slate600
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Progress Indicator
                val strokeValue = progress.percentage / 100f
                Box(
                    modifier = Modifier.size(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { strokeValue },
                        modifier = Modifier.fillMaxSize(),
                        color = CyberIndigo,
                        strokeWidth = 8.dp,
                        trackColor = Slate700
                    )
                    Text(
                        text = String.format("%.0f%%", progress.percentage),
                        color = Slate50,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = progress.name,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate50,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "${formatBytes(progress.processedBytes)} / ${formatBytes(progress.totalBytes)}",
                    fontSize = 12.sp,
                    color = Slate600
                )

                Spacer(modifier = Modifier.height(8.dp))

                // SPEED RATINGS IN NEON MINT
                Box(
                    modifier = Modifier
                        .background(MintNeon.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = String.format("%.2f MB/s", progress.speedMbS),
                        color = MintNeon,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = CoralWarm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("İşlemi İptal Et", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// COMPLETED TRANSFER SUCCESS CARD CELEBRATION
@Composable
fun TransferSuccessOverlay(
    success: TransferState.Success,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MintNeon.copy(alpha = 0.3f), RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Slate800)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Success animated checklist
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .clip(CircleShape)
                        .background(MintNeon.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Success",
                        tint = MintNeon,
                        modifier = Modifier.size(46.dp)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Lojistik Başarılı!",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Slate50
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = success.message,
                    fontSize = 13.sp,
                    color = Slate600,
                    textAlign = TextAlign.Center
                )

                if (success.type == "clipboard") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Metin otomatik olarak karşı cihazın panosuna kopyalandı.",
                        color = CyberCyan,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberIndigo),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Tamamdır", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ERROR ALERT MODAL
@Composable
fun TransferErrorDialog(
    error: TransferState.Error,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CoralWarm.copy(alpha = 0.3f), RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Slate800)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(CoralWarm.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = "Alert",
                        tint = CoralWarm,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Bir Hata Oluştu",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Slate50
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = error.message,
                    fontSize = 13.sp,
                    color = Slate600,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Kapat", fontWeight = FontWeight.Bold, color = Slate50)
                }
            }
        }
    }
}

// DIRECT QR GENERATOR & INTEGRATED CAMERAX SCANNER OVERLAY
@Composable
fun QrShareDialog(
    myIp: String,
    myPort: Int,
    onClose: () -> Unit,
    onDirectConnectAndSend: (String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val qrTabSelection = remember { mutableStateOf(0) } // 0: QR Göster (Alıcı), 1: Kamera Tara (Gönderici)

    Dialog(onDismissRequest = onClose) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(460.dp)
                .border(1.dp, Slate700, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Slate800)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate700.copy(alpha = 0.3f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Güvenli QR Eşleme", fontWeight = FontWeight.Bold, color = Slate50, fontSize = 16.sp)
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Kapat", tint = Slate50)
                    }
                }

                // Inner Tabs (0: QR Göster, 1: Tara)
                TabRow(
                    selectedTabIndex = qrTabSelection.value,
                    containerColor = Slate800,
                    contentColor = CyberIndigo
                ) {
                    Tab(
                        selected = qrTabSelection.value == 0,
                        onClick = { qrTabSelection.value = 0 },
                        text = { Text("QR'ım (Alıcı)", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = qrTabSelection.value == 1,
                        onClick = { qrTabSelection.value = 1 },
                        text = { Text("Kamera Tara (Gönderici)", fontWeight = FontWeight.Bold) }
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrTabSelection.value == 0) {
                        // GENERATE AND RENDER QR BITMAP
                        val qrPayload = remember {
                            val jo = JSONObject().apply {
                                put("ip", myIp)
                                put("port", myPort)
                            }
                            jo.toString()
                        }
                        
                        val qrBitmap = remember(qrPayload) {
                            QrHelper.generateQrCode(qrPayload, 300)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(200.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "My QR Code",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Gönderen cihaz bu QR kodu taratınca doğrudan dosya akışı başlar.",
                                color = Slate600,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        // INTEGRATED CAMERAX PREVIEW
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.5.dp, CyberIndigo, RoundedCornerShape(16.dp))
                        ) {
                            CameraPreview(
                                onQrScanned = { qrResult ->
                                    try {
                                        val jo = JSONObject(qrResult)
                                        val scannedIp = jo.optString("ip", "")
                                        val scannedPort = jo.optInt("port", 0)
                                        if (scannedIp.isNotEmpty() && scannedPort != 0) {
                                            onDirectConnectAndSend(scannedIp, scannedPort)
                                        }
                                    } catch (e: Exception) {
                                        Log.e("QrDialog", "Invalid QR content", e)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            // Crosshair overlay scanning design guide lines
                            Box(
                                modifier = Modifier
                                    .size(160.dp)
                                    .border(2.dp, CyberCyan, RoundedCornerShape(12.dp))
                                    .align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }
}

// CAMERAX NATIVE IMPLEMENTATION INTERFACES
@Composable
fun CameraPreview(
    onQrScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = Executors.newSingleThreadExecutor()

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor, QrHelper.QrImageAnalyzer { text ->
                    previewView.post {
                        onQrScanned(text)
                    }
                })

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (exc: Exception) {
                    Log.e("CameraPreview", "Camera preview layout binding failure", exc)
                }
            }, ContextCompat.getMainExecutor(context))

            previewView
        },
        modifier = modifier
    )
}

// INTRALINE SETTINGS POPUP
@Composable
fun SettingsDialog(
    currentName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: ShareViewModel
) {
    var nameState by remember { mutableStateOf(currentName) }
    
    val autoAccept by viewModel.autoAcceptIncoming.collectAsStateWithLifecycle()
    val onlyWifi by viewModel.onlyWifiState.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOnState.collectAsStateWithLifecycle()
    val showDetailed by viewModel.showDetailedStats.collectAsStateWithLifecycle()
    val highContrast by viewModel.highContrastTheme.collectAsStateWithLifecycle()
    val saveDirectory by viewModel.defaultSaveDirectory.collectAsStateWithLifecycle()
    val localIp by viewModel.localIpState.collectAsStateWithLifecycle()
    
    val profileImageUri by viewModel.profileImageUriState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settingsProfilePicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val success = viewModel.setProfileImageFromUri(uri)
            if (success) {
                Toast.makeText(context, "Profil fotoğrafı güncellendi", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Hata: Fotoğraf ayarlanamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .border(1.dp, Slate700, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Slate800)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "VeloShare Ayarları",
                        fontWeight = FontWeight.Bold,
                        color = Slate50,
                        fontSize = 18.sp
                    )
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        tint = Slate600,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Divider(modifier = Modifier.padding(vertical = 12.dp), color = Slate700)

                // PROFILE PICTURE SECTION
                Text(
                    text = "Profil Fotoğrafınız",
                    fontWeight = FontWeight.SemiBold,
                    color = Slate600,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ProfileAvatar(
                        imageUri = profileImageUri,
                        deviceName = nameState,
                        size = 64.dp,
                        onClick = { settingsProfilePicLauncher.launch("image/*") }
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { settingsProfilePicLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Image,
                                    contentDescription = "Galeri",
                                    tint = Slate50,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text("Galeriden Seç", color = Slate50, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (profileImageUri.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.updateProfileImageUri("") },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Fotoğrafı Kaldır", color = Color.Red.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // DEVICE NAME SECTION
                Text(
                    text = "Ağda Görünen Cihaz Adınız",
                    fontWeight = FontWeight.SemiBold,
                    color = Slate600,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = nameState,
                    onValueChange = { nameState = it },
                    placeholder = { Text("Cihaz Adı Girin") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Slate50,
                        unfocusedTextColor = Slate50,
                        focusedLabelColor = Slate50,
                        unfocusedLabelColor = Slate600,
                        focusedBorderColor = Slate50,
                        unfocusedBorderColor = Slate700,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("device_name_field"),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // TOGGLES SECTION
                Text(
                    text = "Tercihler ve Kontroller",
                    fontWeight = FontWeight.SemiBold,
                    color = Slate600,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Switch 1: Auto Accept
                SettingToggleItem(
                    title = "Otomatik Kabul Et",
                    subtitle = "Gelen tüm transfer isteklerini sormadan onaylar",
                    checked = autoAccept,
                    onCheckedChange = { viewModel.autoAcceptIncoming.value = it }
                )

                // Switch 2: Keep Screen On
                SettingToggleItem(
                    title = "Ekranı Uyanık Tut",
                    subtitle = "Dosya gönderip alırken cihazın uyku moduna geçmesini önler",
                    checked = keepScreenOn,
                    onCheckedChange = { viewModel.keepScreenOnState.value = it }
                )

                // Switch 3: Only Wifi Mode
                SettingToggleItem(
                    title = "Sadece Wi-Fi Modu",
                    subtitle = "Mobil hücresel veri kullanımını sınırlandırarak koruma sağlar",
                    checked = onlyWifi,
                    onCheckedChange = { viewModel.onlyWifiState.value = it }
                )

                // Switch 4: Detailed Transfer Rate
                SettingToggleItem(
                    title = "Gelişmiş Grafikler ve Hız",
                    subtitle = "Aktarım sırasında anlık hız dalgalanmalarını detaylandırır",
                    checked = showDetailed,
                    onCheckedChange = { viewModel.showDetailedStats.value = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // INFORMATIONS SECTION
                Text(
                    text = "Bağlantı ve Sistem Bilgileri",
                    fontWeight = FontWeight.SemiBold,
                    color = Slate600,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))

                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

                InfoRowItem(label = "Yerel Bağlantı IP", value = localIp)
                InfoRowItem(label = "Haberleşme Portu", value = "53210 (TCP)")
                InfoRowItem(label = "Klasör Dizini", value = saveDirectory)
                InfoRowItem(label = "Geliştirici", value = "Voxy")
                InfoRowItem(label = "Sürüm", value = "Voxy")
                
                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF405DE6), // Royal Blue
                                        Color(0xFF5851DB), // Purple
                                        Color(0xFF833AB4), // Deep Violet
                                        Color(0xFFC13584), // Magenta
                                        Color(0xFFE1306C), // Pink-Red
                                        Color(0xFFFD1D1D)  // Red-Orange
                                    )
                                )
                            )
                            .clickable {
                                try {
                                    uriHandler.openUri("https://instagram.com/erayvoxx")
                                } catch (e: Exception) {
                                    // Fallback
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Camera,
                                contentDescription = "Instagram Logo",
                                tint = Slate50,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Instagram'da Takip Et (@erayvoxx)",
                                color = Slate50,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ACTIONS Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate50),
                        border = BorderStroke(1.dp, Slate700),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Kapat", fontSize = 13.sp)
                    }

                    Button(
                        onClick = { onSave(nameState) },
                        modifier = Modifier.weight(1f).testTag("save_settings"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Slate50,
                            contentColor = Slate900
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Kaydet", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(0.8f)) {
            Text(text = title, color = Slate50, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(text = subtitle, color = Slate600, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Slate900,
                checkedTrackColor = Slate50,
                uncheckedThumbColor = Slate600,
                uncheckedTrackColor = Slate700,
                uncheckedBorderColor = Color.Transparent
            ),
            modifier = Modifier.scale(0.8f)
        )
    }
}

@Composable
fun InfoRowItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Slate600, fontSize = 12.sp)
        Text(
            text = value,
            color = Slate50,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(start = 12.dp)
        )
    }
}

// EMPTY VISUAL CHAT LIST PLACEHOLDER
@Composable
fun EmptyStatePlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Inbox, contentDescription = null, tint = Slate700, modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(message, color = Slate600, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

// UTILITY BYTES REPRESENTATION
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val formatted = String.format("%.2f", bytes / Math.pow(1024.0, digitGroups.toDouble()))
    return "$formatted ${units[digitGroups]}"
}

@Composable
fun OfflineGuideStep(stepNumber: String, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Slate50),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber,
                color = Slate900,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = Slate50,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = Slate600,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun SplashScreen(onAnimationFinished: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    
    // Scale animation
    val scale by animateFloatAsState(
        targetValue = if (startAnimation) 1.2f else 0.4f,
        animationSpec = tween(
            durationMillis = 1000,
            easing = FastOutSlowInEasing
        ), label = "logo_scale"
    )

    // Rotation animation
    val rotation by animateFloatAsState(
        targetValue = if (startAnimation) 360f else 0f,
        animationSpec = tween(
            durationMillis = 1200,
            easing = LinearOutSlowInEasing
        ), label = "logo_rotation"
    )

    // Alpha animation
    val alpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(
            durationMillis = 800,
            easing = LinearEasing
        ), label = "logo_alpha"
    )

    // Gradient circle stroke pulse
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "pulse_scale"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(2000)
        onAnimationFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .graphicsLayer {
                        scaleX = scale * pulseScale
                        scaleY = scale * pulseScale
                        rotationZ = rotation
                    }
                    .alpha(alpha),
                contentAlignment = Alignment.Center
            ) {
                // Background futuristic glowing circle ring
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(
                            width = 2.dp,
                            brush = Brush.linearGradient(
                                listOf(Slate50, Slate700)
                            ),
                            shape = CircleShape
                        )
                )
                
                // Actual Share Logo
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    tint = Slate50,
                    modifier = Modifier.size(64.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // App name with fade/scale up
            AnimatedVisibility(
                visible = startAnimation,
                enter = fadeIn(animationSpec = tween(800, delayMillis = 400)) +
                        expandVertically(animationSpec = tween(800, delayMillis = 400))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "VeloShare",
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        color = Slate50,
                        letterSpacing = 4.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Hızlı • Kolay • Çevrimdışı",
                        color = Slate600,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileAvatar(
    imageUri: String,
    deviceName: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val clickableModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF6366F1), // Indigo
                        Color(0xFFA855F7), // Purple
                        Color(0xFFEC4899)  // Pink
                    )
                )
            )
            .border(1.5.dp, Slate50, CircleShape)
            .then(clickableModifier),
        contentAlignment = Alignment.Center
    ) {
        if (imageUri.isNotEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUri)
                    .crossfade(true)
                    .build(),
                contentDescription = "Profil Resmi",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            val initial = if (deviceName.isNotBlank()) {
                deviceName.trim().first().uppercaseChar().toString()
            } else {
                "V"
            }
            Text(
                text = initial,
                color = Slate50,
                fontSize = (size.value * 0.45f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

