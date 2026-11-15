package com.memeturbo.app

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object AppStrings {

    data class ThemePalette(
        val id: Int,
        val nameTr: String,
        val bgHex: Int,
        val headerHex: Int,
        val cardHex: Int,
        val cardStrokeHex: Int,
        val accentHex: Int,
        val secondaryBtnHex: Int,
        val textPrimaryHex: Int,
        val textHighlightHex: Int
    )

    val themes = listOf(
        ThemePalette(
            id = 0,
            nameTr = "🌌 Gece Mavisi (Cyber Dark)",
            bgHex = Color.parseColor("#0B1120"),
            headerHex = Color.parseColor("#151F32"),
            cardHex = Color.parseColor("#1E293B"),
            cardStrokeHex = Color.parseColor("#334155"),
            accentHex = Color.parseColor("#0284C7"),
            secondaryBtnHex = Color.parseColor("#334155"),
            textPrimaryHex = Color.WHITE,
            textHighlightHex = Color.parseColor("#FDE047")
        ),
        ThemePalette(
            id = 1,
            nameTr = "🖤 Tam Siyah (AMOLED Galeri)",
            bgHex = Color.parseColor("#050505"),
            headerHex = Color.parseColor("#101014"),
            cardHex = Color.parseColor("#16161D"),
            cardStrokeHex = Color.parseColor("#2A2A35"),
            accentHex = Color.parseColor("#2563EB"),
            secondaryBtnHex = Color.parseColor("#27272A"),
            textPrimaryHex = Color.WHITE,
            textHighlightHex = Color.parseColor("#38BDF8")
        ),
        ThemePalette(
            id = 2,
            nameTr = "🔮 Neon Mor (Amethyst Glow)",
            bgHex = Color.parseColor("#0F0720"),
            headerHex = Color.parseColor("#1A0E36"),
            cardHex = Color.parseColor("#241447"),
            cardStrokeHex = Color.parseColor("#4C1D95"),
            accentHex = Color.parseColor("#9333EA"),
            secondaryBtnHex = Color.parseColor("#3B1D6E"),
            textPrimaryHex = Color.WHITE,
            textHighlightHex = Color.parseColor("#F472B6")
        ),
        ThemePalette(
            id = 3,
            nameTr = "🌲 Zümrüt Modern (Emerald)",
            bgHex = Color.parseColor("#041311"),
            headerHex = Color.parseColor("#09221E"),
            cardHex = Color.parseColor("#0F2E29"),
            cardStrokeHex = Color.parseColor("#115E59"),
            accentHex = Color.parseColor("#059669"),
            secondaryBtnHex = Color.parseColor("#134E4A"),
            textPrimaryHex = Color.WHITE,
            textHighlightHex = Color.parseColor("#6EE7B7")
        )
    )

    fun getTheme(context: Context): ThemePalette {
        val prefs = context.getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        val idx = prefs.getInt("ui_theme_id", 0).coerceIn(0, themes.lastIndex)
        return themes[idx]
    }

    fun getGridColumns(context: Context): Int {
        val prefs = context.getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("ui_grid_columns", 2).coerceIn(1, 3)
    }

    fun isCompactGalleryCard(context: Context): Boolean {
        val prefs = context.getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("ui_compact_card", false)
    }

    fun getGalleryMode(context: Context): String {
        val prefs = context.getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        return prefs.getString("gallery_source_mode", "device") ?: "device"
    }

    fun isAutoMultiLangAi(context: Context): Boolean {
        val prefs = context.getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("ai_auto_multilang", true)
    }

    fun getLang(context: Context): String {
        val prefs = context.getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("app_lang", null)
        if (!saved.isNullOrBlank()) return saved
        val sys = Locale.getDefault().language.lowercase()
        return if (sys in setOf("tr", "en", "fr", "it", "es", "ru", "de", "ar")) sys else "tr"
    }

    data class Strings(
        val subtitleBanner: String,
        val btnAiAlbum: String,
        val btnPerson: String,
        val btnGPhotos: String,
        val btnSettings: String,
        val btnScanNew: String,
        val btnAiAnalyze: String,
        val tabAll: String,
        val tabVideos: String,
        val tabPhotos: String,
        val searchHint: String,
        val playBtn: String,
        val shareBtn: String,
        val allGalleryLabel: String,
        val settingsTitle: String,
        val onboardingTitle: String,
        val onboardingMsg: String,
        val modeDeviceGallery: String,
        val modeGooglePhotos: String
    )

    fun tr(context: Context): Strings {
        return when (getLang(context)) {
            "en" -> Strings(
                subtitleBanner = "🚀 V17 Global Gallery: AI Smart Albums • Google Photos Mode • Zero Lag",
                btnAiAlbum = "✨ AI Album",
                btnPerson = "👤 Face ID",
                btnGPhotos = "☁️ G.Photos",
                btnSettings = "⚙️ Settings",
                btnScanNew = "Scan New ⚡",
                btnAiAnalyze = "🌐 AI Analyze",
                tabAll = "All",
                tabVideos = "Videos",
                tabPhotos = "Photos",
                searchHint = "Search memes, quotes, scenes, games...",
                playBtn = "▶ Play",
                shareBtn = "Share 🚀",
                allGalleryLabel = "🌟 All Gallery (Everything)",
                settingsTitle = "⚙️ Settings & Customization",
                onboardingTitle = "👋 Welcome! Which Gallery Do You Use Most?",
                onboardingMsg = "Choose how MemeTurbo organizes your videos and photos (you can change this anytime in Settings):",
                modeDeviceGallery = "📱 Phone's Own Gallery (Folder Mode)\nShows local folders (WhatsApp, Download, Movies) separately.",
                modeGooglePhotos = "🌈 Google Photos (Smart AI & Cloud Mode)\nGroups messy downloads into Funny Memes, Quotes & Scenes + Cloud Picker."
            )
            "fr" -> Strings(
                subtitleBanner = "🚀 V17 Galerie Globale : Albums IA • Mode Google Photos • Sans Lag",
                btnAiAlbum = "✨ Album IA",
                btnPerson = "👤 Visage",
                btnGPhotos = "☁️ G.Photos",
                btnSettings = "⚙️ Réglages",
                btnScanNew = "Scanner ⚡",
                btnAiAnalyze = "🌐 Analyse IA",
                tabAll = "Tout",
                tabVideos = "Vidéos",
                tabPhotos = "Photos",
                searchHint = "Rechercher répliques, mèmes, scènes...",
                playBtn = "▶ Lire",
                shareBtn = "Partager 🚀",
                allGalleryLabel = "🌟 Toute la Galerie",
                settingsTitle = "⚙️ Paramètres et Personnalisation",
                onboardingTitle = "👋 Quelle galerie utilisez-vous le plus ?",
                onboardingMsg = "Choisissez comment organiser vos vidéos et photos :",
                modeDeviceGallery = "📱 Galerie du Téléphone (Par Dossier)",
                modeGooglePhotos = "🌈 Google Photos (Albums IA & Cloud)"
            )
            "it" -> Strings(
                subtitleBanner = "🚀 V17 Galleria Globale: Album IA • Modalità Google Foto • Zero Lag",
                btnAiAlbum = "✨ Album IA",
                btnPerson = "👤 Volto",
                btnGPhotos = "☁️ G.Foto",
                btnSettings = "⚙️ Impostazioni",
                btnScanNew = "Scansiona ⚡",
                btnAiAnalyze = "🌐 Analisi IA",
                tabAll = "Tutti",
                tabVideos = "Video",
                tabPhotos = "Foto",
                searchHint = "Cerca battute, meme, scene...",
                playBtn = "▶ Riproduci",
                shareBtn = "Condividi 🚀",
                allGalleryLabel = "🌟 Tutta la Galleria",
                settingsTitle = "⚙️ Impostazioni e Stile",
                onboardingTitle = "👋 Quale galleria usi di più?",
                onboardingMsg = "Scegli come organizzare video e foto:",
                modeDeviceGallery = "📱 Galleria del Telefono (Cartelle)",
                modeGooglePhotos = "🌈 Google Foto (Album IA & Cloud)"
            )
            "es" -> Strings(
                subtitleBanner = "🚀 V17 Galería Global: Álbumes IA • Modo Google Fotos • Cero Lag",
                btnAiAlbum = "✨ Álbum IA",
                btnPerson = "👤 Rostro",
                btnGPhotos = "☁️ G.Fotos",
                btnSettings = "⚙️ Ajustes",
                btnScanNew = "Escanear ⚡",
                btnAiAnalyze = "🌐 Análisis IA",
                tabAll = "Todo",
                tabVideos = "Videos",
                tabPhotos = "Fotos",
                searchHint = "Buscar frases, memes, escenas...",
                playBtn = "▶ Ver",
                shareBtn = "Enviar 🚀",
                allGalleryLabel = "🌟 Toda la Galería",
                settingsTitle = "⚙️ Ajustes y Personalización",
                onboardingTitle = "👋 ¿Qué aplicación de galería usas más?",
                onboardingMsg = "Elige cómo organizar tus videos y fotos:",
                modeDeviceGallery = "📱 Galería del Teléfono (Modo Carpetas)",
                modeGooglePhotos = "🌈 Google Fotos (Álbumes IA y Nube)"
            )
            "ru" -> Strings(
                subtitleBanner = "🚀 V17 Глобальная Галерея: ИИ-Альбомы • Google Фото • Без зависаний",
                btnAiAlbum = "✨ ИИ Альбом",
                btnPerson = "👤 Лицо",
                btnGPhotos = "☁️ G.Фото",
                btnSettings = "⚙️ Настройки",
                btnScanNew = "Сканировать ⚡",
                btnAiAnalyze = "🌐 ИИ Анализ",
                tabAll = "Все",
                tabVideos = "Видео",
                tabPhotos = "Фото",
                searchHint = "Поиск фраз, мемов, сцен...",
                playBtn = "▶ Смотреть",
                shareBtn = "Отправить 🚀",
                allGalleryLabel = "🌟 Вся Галерея",
                settingsTitle = "⚙️ Настройки и Интерфейс",
                onboardingTitle = "👋 Какую галерею вы используете чаще?",
                onboardingMsg = "Выберите режим сортировки видео и фото:",
                modeDeviceGallery = "📱 Галерея телефона (Папки)",
                modeGooglePhotos = "🌈 Google Фото (Умные ИИ-альбомы и Облако)"
            )
            "de" -> Strings(
                subtitleBanner = "🚀 V17 Globale Galerie: KI-Alben • Google Fotos Modus • Kein Ruckeln",
                btnAiAlbum = "✨ KI-Album",
                btnPerson = "👤 Gesicht",
                btnGPhotos = "☁️ G.Fotos",
                btnSettings = "⚙️ Optionen",
                btnScanNew = "Scannen ⚡",
                btnAiAnalyze = "🌐 KI-Analyse",
                tabAll = "Alle",
                tabVideos = "Videos",
                tabPhotos = "Fotos",
                searchHint = "Memes, Zitate, Szenen suchen...",
                playBtn = "▶ Abspielen",
                shareBtn = "Teilen 🚀",
                allGalleryLabel = "🌟 Gesamte Galerie",
                settingsTitle = "⚙️ Einstellungen & Design",
                onboardingTitle = "👋 Welche Galerie nutzt du am meisten?",
                onboardingMsg = "Wähle aus, wie Videos und Fotos sortiert werden:",
                modeDeviceGallery = "📱 Telefon-Galerie (Ordner-Modus)",
                modeGooglePhotos = "🌈 Google Fotos (KI-Alben & Cloud)"
            )
            "ar" -> Strings(
                subtitleBanner = "🚀 V17 معرض ذكي: ألبومات الذكاء الاصطناعي • صور Google • سرعة فائقة",
                btnAiAlbum = "✨ ألبوم ذكي",
                btnPerson = "👤 الوجه",
                btnGPhotos = "☁️ صور Google",
                btnSettings = "⚙️ الإعدادات",
                btnScanNew = "فحص الجديد ⚡",
                btnAiAnalyze = "🌐 تحليل ذكي",
                tabAll = "الكل",
                tabVideos = "فيديو",
                tabPhotos = "صور",
                searchHint = "ابحث عن العبارات، الميمز، المشاهد...",
                playBtn = "▶ تشغيل",
                shareBtn = "مشاركة 🚀",
                allGalleryLabel = "🌟 كل المعرض",
                settingsTitle = "⚙️ الإعدادات والتخصيص",
                onboardingTitle = "👋 أي تطبيق معرض تستخدمه أكثر؟",
                onboardingMsg = "اختر طريقة تنظيم الفيديوهات والصور:",
                modeDeviceGallery = "📱 معرض الهاتف (حسب المجلدات)",
                modeGooglePhotos = "🌈 صور Google (ألبومات الذكاء الاصطناعي والسحابة)"
            )
            else -> Strings(
                subtitleBanner = "🚀 V17 Modern Galeri: ✨ AI Albüm Oluşturucu • 🌈 Google Fotoğraflar Modu • 8 Dil & 4 Tema",
                btnAiAlbum = "✨ AI Albüm",
                btnPerson = "👤 Kişi",
                btnGPhotos = "☁️ G.Foto",
                btnSettings = "⚙️ Ayarlar",
                btnScanNew = "Yenileri Tara ⚡",
                btnAiAnalyze = "🌐 AI Çözümle",
                tabAll = "Tümü",
                tabVideos = "Videolar",
                tabPhotos = "Fotoğraflar",
                searchHint = "Galeri içinde ara (replik, küfür, oyun, kişi)...",
                playBtn = "▶ İzle",
                shareBtn = "Paylaş 🚀",
                allGalleryLabel = "🌟 Tüm Galeri (Hepsi)",
                settingsTitle = "⚙️ Ayarlar & Görünüm Özelleştirme",
                onboardingTitle = "👋 Hangi Galeri Uygulamasını Daha Çok Kullanıyorsunuz?",
                onboardingMsg = "MemeTurbo'nun videolarını ve fotoğraflarını nasıl gruplayacağını seç (daha sonra ⚙️ Ayarlar'dan istediğin an değiştirebilirsin):",
                modeDeviceGallery = "📱 Telefonun Kendi Galerisi (Klasör Modu)\nWhatsApp, Download, Snaptube, kart gibi klasörleri ayrı ayrı listeler.",
                modeGooglePhotos = "🌈 Google Fotoğraflar (Akıllı AI & Bulut Modu)\nKarışık galeriyi otomatik '😂 Komik & Meme', '🗣️ Replikli', '🎮 Oyun' diye ayırır + Buluttan çeker."
            )
        }
    }

    // Custom AI Albums persistence (stored as JSON in SharedPreferences)
    fun getSavedCustomAiAlbums(context: Context): Map<String, Set<String>> {
        val prefs = context.getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("custom_ai_albums_v17", null) ?: return emptyMap()
        val result = linkedMapOf<String, Set<String>>()
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = obj.optJSONArray(k) ?: continue
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i, "")
                    if (p.isNotBlank()) set.add(p)
                }
                if (set.isNotEmpty()) {
                    result[k] = set
                }
            }
        } catch (_: Exception) {}
        return result
    }

    fun saveCustomAiAlbum(context: Context, albumTitle: String, paths: Set<String>) {
        val current = getSavedCustomAiAlbums(context).toMutableMap()
        val fullTitle = if (albumTitle.startsWith("🤖 ")) albumTitle else "🤖 $albumTitle"
        current[fullTitle] = paths
        persistCustomAlbumsMap(context, current)
    }

    fun deleteCustomAiAlbum(context: Context, albumTitle: String) {
        val current = getSavedCustomAiAlbums(context).toMutableMap()
        current.remove(albumTitle)
        persistCustomAlbumsMap(context, current)
    }

    private fun persistCustomAlbumsMap(context: Context, map: Map<String, Set<String>>) {
        val obj = JSONObject()
        for ((k, v) in map) {
            val arr = JSONArray()
            v.forEach { arr.put(it) }
            obj.put(k, arr)
        }
        context.getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("custom_ai_albums_v17", obj.toString())
            .apply()
    }

    private val funnyAndSlangKeywords = listOf(
        "amk", "aq", "amina", "amına", "siker", "sikeyim", "sikiyim", "orospu", "oruspu",
        "piç", "pic", "ulan", "lan", "oğlum", "oglum", "abi", "kanka", "dayı", "dayi",
        "reis", "hoca", "bağır", "bagir", "komik", "meme", "caps", "pov", "edit", "troll",
        "şaka", "saka", "deli", "manyak", "salak", "aptal", "mal", "gerizekalı", "yav",
        "vallahi", "billahi", "kardeş", "kardes", "rac10", "racon", "kürt", "laz", "dede",
        "nine", "kedi", "köpek", "maymun", "haha", "ahaha", "asdasd", "sjsj", "wtf",
        "fuck", "shit", "bro", "bruh", "lol", "lmao", "funny", "cringe", "sigma", "chad"
    )

    private val downloadedSocialFolders = listOf(
        "download", "snaptube", "instagram", "aeroinsta", "tiktok", "whatsapp video",
        "movies", "kart", "rope", "youcut", "inshot", "capcut", "telegram", "twitter", "reddit"
    )

    /**
     * Kullanıcının istediği amaca (örn: "komik ve küfürlü videolar", "oyun kesitleri", "ingilizce memeler")
     * göre taranmış medyaları yerel kurallarla + anahtar kelimelerle filtreler.
     */
    fun filterMemesForGoalHeuristic(allMemes: List<MemeVideo>, goalPrompt: String): List<MemeVideo> {
        val normGoal = VideoIndexer.normalizeTr(goalPrompt.trim())

        val wantsFunnyOrMeme = normGoal.isEmpty() ||
            normGoal.contains("komik") || normGoal.contains("meme") ||
            normGoal.contains("kufur") || normGoal.contains("küfür") ||
            normGoal.contains("sacma") || normGoal.contains("eglence") ||
            normGoal.contains("mizah") || normGoal.contains("indirilen") ||
            normGoal.contains("funny")

        val wantsGames = normGoal.contains("oyun") || normGoal.contains("game") ||
            normGoal.contains("bannerlord") || normGoal.contains("lol") || normGoal.contains("minecraft")

        val wantsPeople = normGoal.contains("kisi") || normGoal.contains("arkadas") ||
            normGoal.contains("ben") || normGoal.contains("mert") || normGoal.contains("selfie") ||
            normGoal.contains("portre")

        val wantsDocs = normGoal.contains("ders") || normGoal.contains("not") ||
            normGoal.contains("belge") || normGoal.contains("sinav") || normGoal.contains("kitap")

        if (wantsFunnyOrMeme && !wantsGames && !wantsPeople && !wantsDocs) {
            return allMemes.filter { m -> isFunnyOrDownloadedMeme(m) }
        }

        if (wantsGames) {
            return allMemes.filter { m ->
                val blob = VideoIndexer.normalizeTr("${m.fileName} ${m.spokenText} ${m.screenText} ${m.visualLabels} ${m.albumName}")
                blob.contains("oyun") || blob.contains("game") || blob.contains("bannerlord") ||
                    blob.contains("lol") || blob.contains("minecraft") || blob.contains("gta") ||
                    blob.contains("valorant") || blob.contains("cs") || blob.contains("steam") ||
                    m.visualLabels.contains("🎮")
            }
        }

        if (wantsPeople) {
            return allMemes.filter { m ->
                val blob = VideoIndexer.normalizeTr("${m.fileName} ${m.spokenText} ${m.screenText} ${m.visualLabels}")
                blob.contains("mert") || blob.contains("arkadas") || blob.contains("portre") ||
                    blob.contains("selfie") || blob.contains("ozcekim") || blob.contains("grup") ||
                    blob.contains("erkek") || blob.contains("kadin") || blob.contains("insan") ||
                    m.visualLabels.contains("🤳") || m.visualLabels.contains("😊") || m.visualLabels.contains("👥")
            }
        }

        if (wantsDocs) {
            return allMemes.filter { m ->
                val blob = VideoIndexer.normalizeTr("${m.fileName} ${m.screenText} ${m.visualLabels} ${m.albumName}")
                blob.contains("belge") || blob.contains("kagit") || blob.contains("not") ||
                    blob.contains("defter") || blob.contains("sinav") || blob.contains("pdf") ||
                    blob.contains("fakul") || blob.contains("ogretmen") || m.screenText.length > 80
            }
        }

        val directMatches = VideoIndexer.smartSearchFilter(allMemes, goalPrompt)
        return if (directMatches.isNotEmpty()) directMatches else allMemes.filter { isFunnyOrDownloadedMeme(it) }
    }

    fun isFunnyOrDownloadedMeme(m: MemeVideo): Boolean {
        val blob = VideoIndexer.normalizeTr("${m.fileName} ${m.spokenText} ${m.screenText} ${m.visualLabels}")
        val albLow = m.albumName.lowercase()

        for (kw in funnyAndSlangKeywords) {
            if (blob.contains(VideoIndexer.normalizeTr(kw))) return true
        }

        val isCameraRecording = m.fileName.startsWith("VID_20", ignoreCase = true) ||
            m.fileName.startsWith("IMG_20", ignoreCase = true) ||
            albLow == "camera" || albLow == "kamera"

        if (m.isVideo && !isCameraRecording) {
            val inSocialFolder = downloadedSocialFolders.any { albLow.contains(it) }
            val hasSpeechOrSub = (m.spokenText.isNotBlank() && !OnlineAiAnalyzer.isMusicOrEmptyTranscript(m.spokenText)) ||
                m.screenText.isNotBlank()
            if (inSocialFolder || hasSpeechOrSub) return true
        }

        if (!m.isVideo && !isCameraRecording && m.screenText.length in 5..140) {
            return true
        }

        return false
    }
}
