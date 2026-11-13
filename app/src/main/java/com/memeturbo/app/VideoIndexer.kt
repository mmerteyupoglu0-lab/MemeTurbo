package com.memeturbo.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.abs
import java.util.concurrent.ConcurrentHashMap

class VideoIndexer(private val context: Context) {

    private val ocrRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.62f).build()
    )
    private val db = MemeDatabase.getInstance(context).memeDao()

    data class AlbumInfo(val name: String, val count: Int, val isAll: Boolean = false)
    data class GalleryMediaItem(val path: String, val name: String, val isVideo: Boolean, val albumName: String)

    companion object {

        private val snippetCache = ConcurrentHashMap<String, String>()
        private val searchBlobCache = ConcurrentHashMap<String, String>()

        fun invalidateCacheFor(filePath: String) {
            snippetCache.remove(filePath)
            searchBlobCache.remove(filePath)
        }

        fun clearAllCaches() {
            snippetCache.clear()
            searchBlobCache.clear()
        }

        fun normalizeTr(text: String): String {
            return text.lowercase()
                .replace('ı', 'i')
                .replace('ğ', 'g')
                .replace('ü', 'u')
                .replace('ş', 's')
                .replace('ö', 'o')
                .replace('ç', 'c')
        }

        private val phoneticReplikFixes = listOf(
            Regex("\\b(oldu mu|oldumu|o rus bu|orospu|oruspu|olur mu|otobüs)\\s+(çocuk|cocuk|çocuğu|cocugu|çocukları|cocuklari)", RegexOption.IGNORE_CASE) to "orospu $2",
            Regex("\\b(zeki adamı|zekayı|zeka da)\\s+budur", RegexOption.IGNORE_CASE) to "zeka da budur",
            Regex("\\bfelaket\\s+(kokusunu|kokuşmuşluğun)\\s+(müslüman|müslümanım)", RegexOption.IGNORE_CASE) to "felaket kokuşmuşluğun",
            Regex("\\b(yüze|yüzde)\\s+delili", RegexOption.IGNORE_CASE) to "yüce göt deliği",
            Regex("\\b(ananı|anani|amını|amini)\\s+(sikerim|sikeyim|sikiyim|siker|skm)", RegexOption.IGNORE_CASE) to "$1 $2",
            Regex("\\b(amına|amina|amuna)\\s+(koyayım|koyayim|koyim|koyayım|koduğum|koydugum)", RegexOption.IGNORE_CASE) to "amına $2",
            Regex("\\b(elma mıyım|elmamıyım|elma miyim)\\s+(konuşcam|konuşacağım|konuscam)", RegexOption.IGNORE_CASE) to "elma mıyım konuşcam",
            Regex("\\b(pişman mıyım|pisman miyim)\\s+(yoo|yo|yok)", RegexOption.IGNORE_CASE) to "pişman mıyım yoo",
            Regex("\\b(kilo kedi|kilo verdi)\\b", RegexOption.IGNORE_CASE) to "kilo verdi",
            Regex("\\b(bağ-kur lu|bağkurlu)\\b", RegexOption.IGNORE_CASE) to "bağırıyor",
            Regex("\\btahvim", RegexOption.IGNORE_CASE) to "takvim"
        )

        fun autoCorrectTurkishReplik(rawSpoken: String): String {
            if (rawSpoken.isBlank() || rawSpoken == "\u200B") return rawSpoken
            var text = rawSpoken.trim()
            if (OnlineAiAnalyzer.isMusicOrEmptyTranscript(text)) return ""
            for ((pattern, replacement) in phoneticReplikFixes) {
                text = pattern.replace(text, replacement)
            }
            return text
        }

        private val weakVoskFillerWords = setOf(
            "bilmiyorum", "bilmiyor", "biliyorum", "bilemedim", "bu", "ne", "ya", "ben", "on", "kim",
            "b", "d", "o", "e", "i", "mu", "mü", "mi", "mı", "ve", "de", "da", "ki", "bi", "bir",
            "ama", "çok", "yok", "var", "hayır", "hayir", "evet", "tamam", "geciyor", "geçiyor",
            "niçin", "nicin", "sadece", "değil", "degil", "ürünleri", "urunleri", "bana", "bunu",
            "müzik", "muzik", "music", "şarkı", "sarki", "altyazı", "altyazi", "alkış",
            "hemen", "sanırım", "diyalog", "türen", "braten", "video", "görsel"
        )

        private val weakMultiWordPhrases = setOf(
            "biliyorum ne bilmiyorum", "biliyorum biz", "biliyorum anna", "biliyorum love",
            "biliyorum anlıyorum", "biliyorum anliyorum", "ne bilmiyorum", "iyi ürün", "mi ürün",
            "müzik çalıyor", "muzik caliyor", "fon müziği",
            "şarkı sözleri, rap ve diyalog", "şarkı sözleri, rap ve diyalog."
        )

        fun isWeakSpokenFiller(spoken: String): Boolean {
            val clean = spoken.replace("\u200B", "")
                .trim()
                .lowercase()
                .trim('.', ',', '!', '?', '-', '_', '[', ']', '(', ')', '♪', '♫', ' ')
            if (clean.isBlank()) return true
            if (clean in weakMultiWordPhrases) return true
            if (clean.contains("şarkı sözleri") && clean.contains("diyalog")) return true
            val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) return true
            if (words.size == 1) {
                val w = words[0]
                if (w.length <= 2 || w in weakVoskFillerWords) return true
            }
            return false
        }

        fun getCachedDisplaySnippet(meme: MemeVideo): String {
            val cacheKey = "${meme.filePath}|${meme.spokenText.hashCode()}|${meme.screenText.hashCode()}|${meme.visualLabels.hashCode()}"
            snippetCache[cacheKey]?.let { return it }

            val rawSpoken = meme.spokenText.replace("\u200B", "").trim()
            val cleanSpoken = if (OnlineAiAnalyzer.isMusicOrEmptyTranscript(rawSpoken)) "" else rawSpoken
            val cleanTitle = cleanFileNameForDisplay(meme.fileName)
            val cleanScreen = filterWatermarks(meme.screenText)
            val cleanVisual = cleanVisualLabelsString(meme.visualLabels)
            val weakSpoken = isWeakSpokenFiller(cleanSpoken)

            val snippet = when {
                cleanSpoken.isNotBlank() && !weakSpoken && cleanScreen.isNotBlank() && !cleanSpoken.equals(cleanScreen, ignoreCase = true) ->
                    "🗣️ $cleanSpoken • 📝 $cleanScreen"
                cleanSpoken.isNotBlank() && !weakSpoken -> "🗣️ $cleanSpoken"
                cleanScreen.isNotBlank() -> "📝 $cleanScreen"
                cleanVisual.isNotBlank() -> if (cleanVisual.any { !it.isLetterOrDigit() && !it.isWhitespace() }) cleanVisual else "🏷️ $cleanVisual"
                cleanSpoken.isNotBlank() -> "🗣️ $cleanSpoken"
                meme.isVideo && cleanTitle.isNotBlank() -> "🎬 $cleanTitle"
                cleanTitle.isNotBlank() -> "🖼️ $cleanTitle"
                else -> if (meme.isVideo) "🎬 Video" else "🖼️ Görsel"
            }
            snippetCache[cacheKey] = snippet
            return snippet
        }

        private fun isOneEditAway(a: String, b: String): Boolean {
            if (abs(a.length - b.length) > 1) return false
            if (a == b) return true
            var i = 0
            var j = 0
            var diffs = 0
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) {
                    i++
                    j++
                } else {
                    diffs++
                    if (diffs > 1) return false
                    if (a.length > b.length) i++
                    else if (b.length > a.length) j++
                    else { i++; j++ }
                }
            }
            if (i < a.length || j < b.length) diffs++
            return diffs <= 1
        }

        /**
         * V13.0 Yazım Hatası + Birleşik/Ayrı ("istesende" <-> "istesen de", "burda" <-> "burada")
         * Toleranslı Akıllı Arama Motoru
         */
        fun smartSearchFilter(items: List<MemeVideo>, query: String): List<MemeVideo> {
            val normQ = normalizeTr(query.trim())
            if (normQ.isEmpty()) return items
            val words = normQ.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) return items
            val spacelessQ = normQ.replace(Regex("[^a-z0-9]"), "")

            val strongMatches = ArrayList<Pair<MemeVideo, Int>>(32)
            val fallbackMatches = ArrayList<Pair<MemeVideo, Int>>(32)

            for (item in items) {
                val blobKey = "${item.filePath}|${item.spokenText.hashCode()}|${item.screenText.hashCode()}|${item.visualLabels.hashCode()}"
                val blob = searchBlobCache.getOrPut(blobKey) {
                    val cleanTitle = cleanFileNameForDisplay(item.fileName)
                    val cleanScreen = filterWatermarks(item.screenText)
                    val cleanVis = cleanVisualLabelsString(item.visualLabels)
                    normalizeTr("$cleanTitle ${item.spokenText} $cleanScreen $cleanVis")
                }

                if (blob.isBlank()) continue
                val spacelessBlob = blob.replace(Regex("[^a-z0-9]"), "")

                // 1. Birleşik / ayrı yazım kontrolü ("istesende" <-> "istesen de", "yazicamda" <-> "yazicam da")
                if (spacelessQ.length >= 5 && spacelessBlob.contains(spacelessQ)) {
                    val exactBonus = if (blob.contains(normQ)) 120 else 60
                    strongMatches.add(item to (220 + exactBonus))
                    continue
                }

                if (words.size == 1) {
                    val w = words[0]
                    val idx = blob.indexOf(w)
                    if (idx >= 0) {
                        val wordBoundaryBonus = if (idx == 0 || !blob[idx - 1].isLetterOrDigit()) 40 else 0
                        strongMatches.add(item to (120 + wordBoundaryBonus))
                    } else if (w.length >= 4) {
                        val stem = w.substring(0, w.length - 1)
                        if (blob.contains(stem)) {
                            fallbackMatches.add(item to 55)
                        } else {
                            val blobWords = blob.split(Regex("[^a-z0-9]+"))
                            if (blobWords.any { bw -> bw.length >= 4 && isOneEditAway(w, bw) }) {
                                fallbackMatches.add(item to 50)
                            }
                        }
                    }
                } else {
                    val meaningfulWords = words.filterIndexed { index, w ->
                        w.length >= 2 || index == words.lastIndex
                    }.ifEmpty { words }

                    var exactOrPrefixMatched = 0
                    var fuzzyMatched = 0
                    var blobWordsCache: List<String>? = null

                    for ((idx, w) in meaningfulWords.withIndex()) {
                        if (blob.contains(w)) {
                            exactOrPrefixMatched++
                        } else if (w.length >= 4) {
                            val bWords = blobWordsCache ?: blob.split(Regex("[^a-z0-9]+")).also { blobWordsCache = it }
                            val stem = w.substring(0, minOf(w.length - 1, 5))
                            if (bWords.any { bw -> bw.startsWith(stem) || (bw.length >= 4 && isOneEditAway(w, bw)) }) {
                                fuzzyMatched++
                            }
                        } else if (idx == meaningfulWords.lastIndex && w.length >= 2 && blob.contains(w)) {
                            exactOrPrefixMatched++
                        }
                    }

                    if (exactOrPrefixMatched == meaningfulWords.size) {
                        val phraseBonus = if (blob.contains(normQ)) 100 else 0
                        strongMatches.add(item to (200 + phraseBonus))
                    } else if (exactOrPrefixMatched + fuzzyMatched == meaningfulWords.size && (exactOrPrefixMatched > 0 || fuzzyMatched >= 2)) {
                        strongMatches.add(item to (140 + exactOrPrefixMatched * 20 + fuzzyMatched * 10))
                    } else if (exactOrPrefixMatched > 0 || fuzzyMatched > 0) {
                        val longWordMatched = meaningfulWords.any { it.length >= 3 && (blob.contains(it)) }
                        if (longWordMatched || fuzzyMatched > 0) {
                            val score = exactOrPrefixMatched * 30 + fuzzyMatched * 15
                            fallbackMatches.add(item to score)
                        }
                    }
                }
            }

            strongMatches.sortByDescending { it.second }
            fallbackMatches.sortByDescending { it.second }

            return if (strongMatches.isNotEmpty()) {
                val combined = ArrayList<MemeVideo>(strongMatches.size + 6)
                for (p in strongMatches) combined.add(p.first)
                for (p in fallbackMatches.take(6)) {
                    if (p.second >= 40) combined.add(p.first)
                }
                combined
            } else {
                fallbackMatches.take(24).map { it.first }
            }
        }

        private val foreignScriptRegex = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{InArabic}]+")

        private val spamAndWatermarkPatterns = listOf(
            Regex("@[a-zA-Z0-9._]+", RegexOption.IGNORE_CASE),
            Regex("[0-9]*galerim[a-zA-Z0-9._]*", RegexOption.IGNORE_CASE),
            Regex(".*galeriko.*", RegexOption.IGNORE_CASE),
            Regex(".*rraene.*", RegexOption.IGNORE_CASE),
            Regex(".*humor.*", RegexOption.IGNORE_CASE),
            Regex(".*mizah.*", RegexOption.IGNORE_CASE),
            Regex(".*tv.*anime.*", RegexOption.IGNORE_CASE),
            Regex(".*instagram.*", RegexOption.IGNORE_CASE),
            Regex(".*tiktok.*", RegexOption.IGNORE_CASE),
            Regex(".*sayfa.*", RegexOption.IGNORE_CASE),
            Regex(".*arsiv.*", RegexOption.IGNORE_CASE),
            Regex(".*arşiv.*", RegexOption.IGNORE_CASE),
            Regex(".*reels.*", RegexOption.IGNORE_CASE),
            Regex(".*kesfet.*", RegexOption.IGNORE_CASE),
            Regex(".*keşfet.*", RegexOption.IGNORE_CASE),
            Regex(".*takip.*", RegexOption.IGNORE_CASE),
            Regex(".*gatts.*", RegexOption.IGNORE_CASE),
            Regex(".*inshot.*", RegexOption.IGNORE_CASE),
            Regex(".*paqutex.*", RegexOption.IGNORE_CASE),
            Regex(".*canerimo.*", RegexOption.IGNORE_CASE),
            Regex(".*begenenler.*", RegexOption.IGNORE_CASE),
            Regex(".*beğenenler.*", RegexOption.IGNORE_CASE),
            Regex(".*toosted.*", RegexOption.IGNORE_CASE),
            Regex(".*ataner.*", RegexOption.IGNORE_CASE),
            Regex(".*odunyanin.*", RegexOption.IGNORE_CASE),
            Regex(".*damladila.*", RegexOption.IGNORE_CASE),
            Regex(".*koftilenk.*", RegexOption.IGNORE_CASE),
            Regex(".*yourmood.*", RegexOption.IGNORE_CASE),
            Regex(".*enesyildiz.*", RegexOption.IGNORE_CASE),
            Regex(".*mrzaersa.*", RegexOption.IGNORE_CASE),
            Regex(".*shitrazse.*", RegexOption.IGNORE_CASE),
            Regex(".*rooelmi.*", RegexOption.IGNORE_CASE),
            Regex(".*bufutbolist.*", RegexOption.IGNORE_CASE),
            Regex(".*depresitsporta.*", RegexOption.IGNORE_CASE),
            Regex(".*saodspude.*", RegexOption.IGNORE_CASE),
            Regex(".*bvdolar.*", RegexOption.IGNORE_CASE),
            Regex(".*kraliny.*", RegexOption.IGNORE_CASE),
            Regex(".*subscribe.*", RegexOption.IGNORE_CASE),
            Regex(".*vogue\\s*world.*", RegexOption.IGNORE_CASE),
            Regex(".*hollywood.*", RegexOption.IGNORE_CASE),
            Regex(".*gracieabrams.*", RegexOption.IGNORE_CASE),
            Regex(".*dojacat.*", RegexOption.IGNORE_CASE),
            Regex(".*bellingham.*", RegexOption.IGNORE_CASE),
            Regex(".*stepped into the crowd.*", RegexOption.IGNORE_CASE),
            Regex(".*took a turn in the audience.*", RegexOption.IGNORE_CASE),
            Regex(".*biyografiden kanala.*", RegexOption.IGNORE_CASE),
            Regex(".*videoları indirmek için.*", RegexOption.IGNORE_CASE),
            Regex(".*one piece.*", RegexOption.IGNORE_CASE),
            Regex(".*solo leveling.*", RegexOption.IGNORE_CASE),
            Regex(".*abone hedefi.*", RegexOption.IGNORE_CASE),
            Regex(".*batuhanfurkan.*", RegexOption.IGNORE_CASE),
            Regex(".*üperlig.*", RegexOption.IGNORE_CASE),
            Regex(".*süperlig.*", RegexOption.IGNORE_CASE),
            Regex(".*ülker\\s*stad.*", RegexOption.IGNORE_CASE),
            Regex(".*ulker\\s*stad.*", RegexOption.IGNORE_CASE),
            Regex(".*bwin.*", RegexOption.IGNORE_CASE),
            Regex(".*heineken.*", RegexOption.IGNORE_CASE),
            Regex(".*hankook.*", RegexOption.IGNORE_CASE),
            Regex(".*ukicard.*", RegexOption.IGNORE_CASE),
            Regex(".*rezervcom.*", RegexOption.IGNORE_CASE),
            Regex(".*www\\..*", RegexOption.IGNORE_CASE),
            Regex(".*\\.com.*", RegexOption.IGNORE_CASE),
            Regex(".*\\.co\\b.*", RegexOption.IGNORE_CASE)
        )

        private val singleWordTrash = setOf(
            "canli", "canlı", "live", "manti", "mantı", "polis", "avea", "opet", "avis",
            "aygaz", "bwin", "respect", "ria", "eh", "ff", "f.f", "on", "tr", "hd", "mp4",
            "part", "edit", "video", "image", "webp", "jpg", "bmvel", "bmvela", "privat",
            "private", "scalarx", "scalalx", "baueli", "inshot", "braten", "hemen", "türen"
        )

        private val oldEnglishJunkWords = setOf(
            "skin", "mouth", "hair", "beard", "moustache", "muscle", "flesh", "dude",
            "selfie", "smile", "eyelash", "eyebrow", "ear", "nose", "chin", "forehead",
            "cheek", "neck", "hand", "finger", "arm", "leg", "person", "man", "woman",
            "boy", "girl", "fun", "cool", "happy", "sitting", "interaction", "couch",
            "chair", "event", "jacket", "pattern", "room", "toy", "metal", "wall", "hat",
            "curtain", "tableware", "wine", "alcohol", "leisure", "shorts", "pants",
            "wetsuit", "vacation", "petal", "flower", "jeans", "plant", "farm", "building",
            "statue", "aircraft", "helicopter", "race", "van", "tire", "helmet", "concert",
            "crowd", "glasses", "sunglasses", "windshield", "goggles", "field", "sky",
            "prairie", "soil", "monochrome", "space", "screenshot", "insect", "vehicle",
            "bird", "cat", "dog", "animal", "pet", "carnivore", "mammal", "gülümseme",
            "gülme", "komik", "özçekim", "ben", "kendim", "arkadaşlar", "ortam", "kalabalık",
            "grup", "bina", "mimari", "yapı", "araba", "otomobil", "araç", "kedi", "pisi",
            "kedicik", "evcil", "hayvan", "köpek", "it", "kuçu", "azgın"
        )

        private val strictTurkishVisualMap = mapOf(
            "cat" to "🐱 Kedi",
            "kitten" to "🐱 Yavru Kedi",
            "felidae" to "🐱 Kedi",
            "small to medium-sized cats" to "🐱 Kedi",
            "dog" to "🐶 Köpek",
            "puppy" to "🐶 Yavru Köpek",
            "canidae" to "🐶 Köpek",
            "monkey" to "🐵 Maymun",
            "macaque" to "🐵 Maymun",
            "primate" to "🐵 Maymun",
            "ape" to "🐵 Maymun",
            "horse" to "🐎 At",
            "bear" to "🐻 Ayı",
            "bird" to "🐦 Kuş",
            "duck" to "🦆 Ördek",
            "chicken" to "🐔 Tavuk",
            "rooster" to "🐓 Horoz",
            "fish" to "🐟 Balık",
            "frog" to "🐸 Kurbağa",
            "toad" to "🐸 Kurbağa",
            "car" to "🚗 Araba",
            "motor vehicle" to "🚗 Araba / Araç",
            "sports car" to "🏎️ Spor Araba",
            "bus" to "🚌 Otobüs",
            "motorcycle" to "🏍️ Motosiklet",
            "bicycle" to "🚲 Bisiklet",
            "snow" to "❄️ Karlı Sahne",
            "winter" to "❄️ Kış / Kar",
            "football" to "⚽ Futbol / Maç",
            "soccer" to "⚽ Futbol / Maç",
            "stadium" to "🏟️ Stadyum / Maç",
            "ball" to "⚽ Futbol Topu",
            "sports equipment" to "⚽ Spor / Maç",
            "water bottle" to "🍼 Su Şişesi",
            "plastic bottle" to "🍼 Su Şişesi",
            "bottle" to "🍼 Şişe",
            "sunflower" to "🌻 Ayçiçeği Tarlası",
            "laser" to "🔴 Lazer",
            "light" to "💡 Işık / Lazer",
            "games" to "🎮 Oyun Kesiti",
            "pc game" to "🎮 Oyun Kesiti",
            "video game software" to "🎮 Oyun Kesiti",
            "cartoon" to "🎨 Çizgi Film / Animasyon",
            "animation" to "🎨 Animasyon",
            "animated cartoon" to "🎨 Çizgi Film / Animasyon"
        )

        private val photoTurkishSceneMap = mapOf(
            "selfie" to "🤳 Özçekim / Portre",
            "portrait" to "🤳 Portre Fotoğraf",
            "smile" to "😊 Gülümseyen Portre",
            "crowd" to "👥 Kalabalık / Topluluk",
            "event" to "🏛️ Salon / Etkinlik",
            "audience" to "🏛️ Konferans / Kalabalık",
            "room" to "🏠 İç Mekan / Oda",
            "building" to "🏢 Bina / Dış Mekan",
            "architecture" to "🏢 Bina / Sokak",
            "road" to "🛣️ Sokak / Kaldırım",
            "asphalt" to "🛣️ Sokak / Kaldırım",
            "tree" to "🌳 Park / Ağaçlık",
            "grass" to "🌿 Çimen / Park",
            "lawn" to "🌿 Çimlerde Ortam",
            "plant" to "🌿 Doğa / Park",
            "leisure" to "🏕️ Açık Hava / Dinlenme",
            "recreation" to "🏕️ Açık Hava / Etkinlik",
            "muscle" to "🏋️ Spor / Antrenman",
            "physical fitness" to "🏋️ Spor Salonu",
            "paper" to "📄 Kağıt / Not / Belge",
            "document" to "📄 Belge / Yazı",
            "text" to "📝 Yazılı Görsel",
            "screenshot" to "📱 Ekran Görüntüsü",
            "food" to "🍽️ Yemek / Sofra",
            "tableware" to "🍽️ Masa / Sofra"
        )

        private val knownMemeFigures = mapOf(
            "ardaturan" to "Arda Turan",
            "arda turan" to "Arda Turan",
            "fatihterim" to "Fatih Terim",
            "fatih terim" to "Fatih Terim",
            "sinanengin" to "Sinan Engin",
            "sinan engin" to "Sinan Engin",
            "ahmetcakar" to "Ahmet Çakar",
            "ahmet çakar" to "Ahmet Çakar",
            "besimtibuk" to "Besim Tibuk",
            "besim tibuk" to "Besim Tibuk",
            "htalks" to "H-Talks Hasan Arda",
            "h-talks" to "H-Talks Hasan Arda",
            "hasanarda" to "H-Talks Hasan Arda",
            "kaanozdil" to "Kaan Özdil",
            "kaan özdil" to "Kaan Özdil",
            "tanertolga" to "Taner Tolga Tarlacı",
            "taner tolga" to "Taner Tolga Tarlacı",
            "savascebeci" to "Savaş Cebeci",
            "savaş cebeci" to "Savaş Cebeci",
            "cenkhoca" to "Cenk Hoca",
            "cenk hoca" to "Cenk Hoca",
            "sonmezreis" to "Sönmez Reis",
            "sönmez reis" to "Sönmez Reis",
            "alikoc" to "Ali Koç",
            "ali koç" to "Ali Koç",
            "sansarsalvo" to "Sansar Salvo",
            "sansar salvo" to "Sansar Salvo",
            "evrimagaci" to "Evrim Ağacı",
            "evrim ağacı" to "Evrim Ağacı",
            "polatalemdar" to "Polat Alemdar"
        )

        fun cleanFileNameForDisplay(rawName: String): String {
            var s = rawName
                .replace(Regex("\\.(mp4|jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\((MP4|720P_HD|360P|1080P|WEBP|JPG|PNG)\\)", RegexOption.IGNORE_CASE), "")
                .replace(foreignScriptRegex, " ")

            val cutPatterns = listOf(
                "_keşfet", "_kesfet", "#keşfet", "#kesfet", "_fyp", "#fyp", "fyp",
                "Videoları indirmek için", "Daha fazlası için takip", "Takip etmeyi unutmayın",
                "Jude Bellingham", "Vogue World", "stepped into the crowd", "took a turn in the audience",
                "ONE PIECE", "Solo Leveling", "THINKER", "VIBES", "gracieabrams"
            )
            for (pat in cutPatterns) {
                val idx = s.indexOf(pat, ignoreCase = true)
                if (idx >= 0) {
                    s = s.substring(0, idx)
                }
            }

            val trimmed = s.trim()
            val lower = trimmed.lowercase()
            if (lower.startsWith("vid_") || lower.startsWith("vid-") ||
                lower.startsWith("img_") || lower.startsWith("img-") ||
                lower.startsWith("inshot") || lower.startsWith("youcut") ||
                lower.startsWith("lv_0_") || lower.startsWith("screenshot") ||
                lower.startsWith("instagram") || lower.startsWith("unconfirmed") ||
                lower.startsWith("snap") || lower.startsWith(" whatsapp") ||
                lower.matches(Regex("^[a-z0-9_.]+-\\d{8}-\\d+$")) ||
                lower.matches(Regex("^[0-9a-f]{16,}$")) ||
                lower.matches(Regex("^\\d[a-z0-9_\\s-]{10,}$"))
            ) {
                for ((key, personName) in knownMemeFigures) {
                    if (lower.contains(key)) return personName
                }
                return ""
            }

            val cleaned = trimmed
                .replace(Regex("_\\d+$"), "")
                .replace('_', ' ')
                .replace(Regex("\\s+"), " ")
                .trim(' ', '.', '-', '|')

            val letterCount = cleaned.count { it.isLetter() }
            if (letterCount < 4) return ""
            if (cleaned.lowercase() in singleWordTrash) return ""

            return cleaned
        }

        /**
         * Instagram/TikTok simgesinin yanındaki kullanıcı adlarını (BDAMLADILA, 6GALERIKKOO,
         * ENESYILDIZ99, BKOFTILENK vb.) ve anlamsız harf yığınlarını tamamen temizler!
         */
        fun filterWatermarks(rawText: String): String {
            if (rawText.isBlank()) return ""
            val lines = rawText
                .replace(foreignScriptRegex, " ")
                .split("\n", "|")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val filtered = lines.filter { line ->
                val cleanLine = line.trim(' ', '.', '-', '_', '|', ':', ';', ',', '!', '?')
                val letterCount = cleanLine.count { it.isLetter() }
                val lower = cleanLine.lowercase()

                if (lower.contains("h-talks") || lower.contains("htalks")) {
                    return@filter true
                }

                if (letterCount < 4) return@filter false
                if (lower in singleWordTrash) return@filter false
                if (cleanLine.matches(Regex("^[0-9\\W_]+$"))) return@filter false

                // Tamamı büyük harf/rakam olan Instagram sayfa adı / logo çöpleri (ör: BDAMLADILA, 6GALERIKKOO, GOKSJXKCJDCH)
                val tokens = cleanLine.split(Regex("\\s+"))
                if (tokens.all { tok -> tok.length >= 4 && tok.all { !it.isLetter() || it.isUpperCase() } && (tok.length <= 18 && !cleanLine.contains(" ") || tok.any { it.isDigit() }) }) {
                    return@filter false
                }
                if (tokens.size == 1 && cleanLine.length >= 6 && cleanLine.all { !it.isLetter() || it.isUpperCase() }) {
                    return@filter false
                }

                val isShortAllCapsLogo = cleanLine.length <= 6 && cleanLine.all { !it.isLetter() || it.isUpperCase() } && !cleanLine.contains(" ")
                if (isShortAllCapsLogo) return@filter false

                spamAndWatermarkPatterns.none { pattern -> pattern.containsMatchIn(cleanLine) }
            }

            // Satır içi tekil BÜYÜKHARF Instagram kullanıcı adı tokenlarını da temizle
            val cleanedLines = filtered.map { line ->
                line.split(Regex("\\s+")).filter { tok ->
                    val stripped = tok.trim('.', ',', ':', ';', '!', '?')
                    val isInstaHandleToken = stripped.length >= 7 &&
                        stripped.all { !it.isLetter() || it.isUpperCase() } &&
                        stripped.any { it.isLetter() }
                    !isInstaHandleToken && spamAndWatermarkPatterns.none { it.containsMatchIn(stripped) }
                }.joinToString(" ")
            }.filter { it.count { c -> c.isLetter() } >= 4 }

            val joined = cleanedLines.distinct().joinToString(" ").replace(Regex("\\s+"), " ").trim()
            return if (joined.count { it.isLetter() } >= 4) joined else ""
        }

        fun cleanVisualLabelsString(rawLabels: String): String {
            if (rawLabels.isBlank()) return ""
            if (rawLabels.contains("🖼️") || rawLabels.contains("🏷️") ||
                rawLabels.contains("🐱") || rawLabels.contains("🐶") || rawLabels.contains("🐵") ||
                rawLabels.contains("🐎") || rawLabels.contains("🐻") || rawLabels.contains("🚗") ||
                rawLabels.contains("⚽") || rawLabels.contains("🌻") || rawLabels.contains("🎮")
            ) {
                return rawLabels
            }
            val words = rawLabels.split(Regex("\\s+")).filter { it.isNotBlank() }
            val junkCount = words.count { it.lowercase() in oldEnglishJunkWords }
            if (junkCount > 0 && junkCount >= words.size / 2) {
                return ""
            }
            val kept = words.filter { it.lowercase() !in oldEnglishJunkWords }
            return kept.joinToString(" ").trim()
        }
    }

    fun getOrInitVoskModel(onStatus: ((String) -> Unit)? = null): Model? {
        val baseDir = File(context.filesDir, "vosk_tr_model_v9")
        var finalMdl = findFileRecursively(baseDir, "final.mdl")

        if (finalMdl == null) {
            try {
                onStatus?.invoke("📦 Türkçe ses modeli çıkartılıyor (ilk kurulum)...")
                baseDir.deleteRecursively()
                baseDir.mkdirs()

                val assetList = context.assets.list("")?.toSet() ?: emptySet()
                if ("vosk_tr.zip" in assetList) {
                    context.assets.open("vosk_tr.zip").use { rawIn ->
                        ZipInputStream(rawIn.buffered()).use { zis ->
                            var entry = zis.nextEntry
                            val buffer = ByteArray(16384)
                            while (entry != null) {
                                val outFile = File(baseDir, entry.name)
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos ->
                                        var len: Int
                                        while (zis.read(buffer).also { len = it } > 0) {
                                            fos.write(buffer, 0, len)
                                        }
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    }
                } else {
                    copyAssetsFolder("models/vosk-model-small-tr", baseDir)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                return null
            }
            finalMdl = findFileRecursively(baseDir, "final.mdl")
        }

        if (finalMdl == null) return null

        val modelRootDir = if (finalMdl.parentFile?.name == "am") {
            finalMdl.parentFile?.parentFile ?: baseDir
        } else {
            finalMdl.parentFile ?: baseDir
        }

        return try {
            Model(modelRootDir.absolutePath)
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private fun findFileRecursively(dir: File, targetName: String): File? {
        if (!dir.exists()) return null
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isFile && f.name == targetName && f.length() > 100_000L) {
                return f
            } else if (f.isDirectory) {
                val found = findFileRecursively(f, targetName)
                if (found != null) return found
            }
        }
        return null
    }

    private fun copyAssetsFolder(assetPath: String, destDir: File) {
        val assets = context.assets.list(assetPath) ?: return
        if (assets.isEmpty()) return
        if (!destDir.exists()) destDir.mkdirs()
        for (item in assets) {
            val subAssetPath = "$assetPath/$item"
            val subDest = File(destDir, item)
            val subAssets = context.assets.list(subAssetPath)
            if (subAssets != null && subAssets.isNotEmpty()) {
                copyAssetsFolder(subAssetPath, subDest)
            } else {
                context.assets.open(subAssetPath).use { inStream ->
                    FileOutputStream(subDest).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
            }
        }
    }

    suspend fun cleanExistingDatabaseRecords() = withContext(Dispatchers.IO) {
        val allMemes = db.getAllMemes()
        val needsAlbumBackfill = allMemes.any { it.albumName.isBlank() }
        val pathToAlbum = if (needsAlbumBackfill) {
            queryGalleryMedia(AlbumInfo("all", 0, isAll = true)).associate { it.path to it.albumName }
        } else {
            emptyMap()
        }

        for (item in allMemes) {
            if (!File(item.filePath).exists()) {
                db.deleteByPath(item.filePath)
                invalidateCacheFor(item.filePath)
                continue
            }
            var cleanedSpoken = autoCorrectTurkishReplik(item.spokenText)
            if (OnlineAiAnalyzer.isMusicOrEmptyTranscript(cleanedSpoken) ||
                cleanedSpoken.contains("şarkı sözleri", ignoreCase = true) && cleanedSpoken.contains("diyalog", ignoreCase = true)
            ) {
                cleanedSpoken = ""
            }
            val cleanedScreen = filterWatermarks(item.screenText)
            val cleanedVisual = cleanVisualLabelsString(item.visualLabels)
            if (cleanedSpoken != item.spokenText || cleanedScreen != item.screenText || cleanedVisual != item.visualLabels) {
                db.updateFields(
                    path = item.filePath,
                    spokenText = cleanedSpoken,
                    screenText = cleanedScreen,
                    visualLabels = cleanedVisual
                )
            }
            if (item.albumName.isBlank()) {
                val foundAlbum = pathToAlbum[item.filePath]
                if (!foundAlbum.isNullOrBlank()) {
                    db.updateAlbumName(item.filePath, foundAlbum)
                }
            }
        }
    }

    suspend fun getAvailableAlbums(): List<AlbumInfo> = withContext(Dispatchers.IO) {
        val albums = mutableMapOf<String, Int>()
        var totalAllCount = 0

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.BUCKET_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketName = cursor.getString(bucketIdx) ?: continue
                albums[bucketName] = (albums[bucketName] ?: 0) + 1
                totalAllCount++
            }
        }

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketName = cursor.getString(bucketIdx) ?: continue
                albums[bucketName] = (albums[bucketName] ?: 0) + 1
                totalAllCount++
            }
        }

        val result = mutableListOf<AlbumInfo>()
        result.add(AlbumInfo("🌟 Tüm Galeri (Hepsi)", totalAllCount, isAll = true))
        result.addAll(albums.map { AlbumInfo(it.key, it.value) }.sortedByDescending { it.count })
        return@withContext result
    }

    fun queryGalleryMediaPublic(albumInfo: AlbumInfo): List<GalleryMediaItem> = queryGalleryMedia(albumInfo)

    private fun queryGalleryMedia(albumInfo: AlbumInfo): List<GalleryMediaItem> {
        val mediaList = mutableListOf<GalleryMediaItem>()
        val isAll = albumInfo.isAll
        val selection = if (isAll) null else "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?"
        val selectionArgs = if (isAll) null else arrayOf(albumInfo.name)

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.BUCKET_DISPLAY_NAME),
            selection, selectionArgs, "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: "video"
                val bucket = cursor.getString(bucketIdx) ?: ""
                mediaList.add(GalleryMediaItem(path, name, true, bucket))
            }
        }

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            selection, selectionArgs, "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: "image"
                val bucket = cursor.getString(bucketIdx) ?: ""
                mediaList.add(GalleryMediaItem(path, name, false, bucket))
            }
        }
        return mediaList
    }

    suspend fun extractImageData(file: File, albumName: String = ""): MemeVideo {
        var localScreenText = ""
        var offlineVisualScene = ""

        try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
            if (bitmap != null) {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val ocrResult = ocrRecognizer.process(inputImage).await()
                localScreenText = filterWatermarks(ocrResult.text)
                offlineVisualScene = extractPhotoTurkishSceneLabels(inputImage)
                bitmap.recycle()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val onlineRes = OnlineAiAnalyzer.analyzeImageWithAi(context, file, localOcrHint = localScreenText)
        if (onlineRes != null && (onlineRes.screenText.isNotBlank() || onlineRes.visualLabels.isNotBlank())) {
            return MemeVideo(
                filePath = file.absolutePath,
                fileName = file.nameWithoutExtension,
                durationMs = 0L,
                spokenText = "",
                screenText = onlineRes.screenText.ifBlank { localScreenText },
                visualLabels = onlineRes.visualLabels.ifBlank { offlineVisualScene },
                isVideo = false,
                lastModified = file.lastModified(),
                albumName = albumName
            )
        }

        return MemeVideo(
            filePath = file.absolutePath,
            fileName = file.nameWithoutExtension,
            durationMs = 0L,
            spokenText = "",
            screenText = localScreenText,
            visualLabels = offlineVisualScene,
            isVideo = false,
            lastModified = file.lastModified(),
            albumName = albumName
        )
    }

    private suspend fun extractPhotoTurkishSceneLabels(inputImage: InputImage): String {
        val found = mutableSetOf<String>()
        try {
            val results = imageLabeler.process(inputImage).await()
            for (lbl in results) {
                val key = lbl.text.lowercase()
                val trName = strictTurkishVisualMap[key] ?: photoTurkishSceneMap[key]
                if (trName != null) {
                    found.add(trName)
                }
            }
        } catch (_: Exception) {}
        return found.take(2).joinToString(" • ")
    }

    suspend fun extractLocalVideoOcrAndScene(file: File): Triple<Long, String, String> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        val screenTexts = mutableListOf<String>()
        var firstFrameBitmapForScene: Bitmap? = null

        try {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val effectiveDurUs = (durationMs.coerceIn(1500L, 12000L)) * 1000L
            val sampleTimesUs = listOf(
                (effectiveDurUs * 0.18).toLong(),
                (effectiveDurUs * 0.50).toLong(),
                (effectiveDurUs * 0.80).toLong()
            )

            for ((idx, timeUs) in sampleTimesUs.withIndex()) {
                val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue

                val maxDim = maxOf(rawBitmap.width, rawBitmap.height)
                val bitmap = if (maxDim > 640) {
                    val scale = 640f / maxDim
                    val scaled = Bitmap.createScaledBitmap(
                        rawBitmap,
                        (rawBitmap.width * scale).toInt().coerceAtLeast(1),
                        (rawBitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                    if (scaled != rawBitmap) rawBitmap.recycle()
                    scaled
                } else {
                    rawBitmap
                }

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val result = ocrRecognizer.process(inputImage).await()
                val cleanLines = filterWatermarks(result.text)
                if (cleanLines.isNotEmpty() && cleanLines !in screenTexts) {
                    screenTexts.add(cleanLines)
                }

                if (idx == 0 && firstFrameBitmapForScene == null) {
                    firstFrameBitmapForScene = bitmap
                } else {
                    bitmap.recycle()
                }
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        var offlineScene = ""
        if (firstFrameBitmapForScene != null) {
            if (screenTexts.isEmpty()) {
                offlineScene = extractStrictTurkishSceneLabels(InputImage.fromBitmap(firstFrameBitmapForScene!!, 0))
            }
            firstFrameBitmapForScene?.recycle()
        }

        Triple(durationMs, screenTexts.joinToString(" | "), offlineScene)
    }

    suspend fun extractVideoData(file: File, voskModel: Model?, albumName: String = ""): MemeVideo = coroutineScope {
        val localVisualDeferred = async(Dispatchers.IO) {
            extractLocalVideoOcrAndScene(file)
        }

        val (localDurMs, localScreenText, localSceneLabel) = localVisualDeferred.await()

        val onlineRes = OnlineAiAnalyzer.analyzeVideoWithAi(
            context = context,
            videoFile = file,
            indexer = this@VideoIndexer,
            localOcrHint = localScreenText,
            forceVision = false
        )

        if (onlineRes != null && (onlineRes.spokenText.isNotBlank() || onlineRes.screenText.isNotBlank() || onlineRes.visualLabels.isNotBlank())) {
            val mergedScreen = when {
                onlineRes.screenText.length >= localScreenText.length && onlineRes.screenText.isNotBlank() -> onlineRes.screenText
                localScreenText.isNotBlank() -> localScreenText
                else -> onlineRes.screenText
            }
            val mergedVisual = onlineRes.visualLabels.ifBlank { localSceneLabel }
            return@coroutineScope MemeVideo(
                filePath = file.absolutePath,
                fileName = file.nameWithoutExtension,
                durationMs = if (onlineRes.durationMs > 0L) onlineRes.durationMs else localDurMs,
                spokenText = onlineRes.spokenText,
                screenText = mergedScreen,
                visualLabels = mergedVisual,
                isVideo = true,
                lastModified = file.lastModified(),
                albumName = albumName
            )
        }

        val offlineSpoken = if (voskModel != null) {
            withContext(Dispatchers.Default) { transcribeVideoAudio(file, voskModel) }
        } else {
            ""
        }

        MemeVideo(
            filePath = file.absolutePath,
            fileName = file.nameWithoutExtension,
            durationMs = localDurMs,
            spokenText = offlineSpoken,
            screenText = localScreenText,
            visualLabels = localSceneLabel,
            isVideo = true,
            lastModified = file.lastModified(),
            albumName = albumName
        )
    }

    suspend fun extractStrictTurkishSceneLabels(inputImage: InputImage): String {
        val found = mutableSetOf<String>()
        try {
            val results = imageLabeler.process(inputImage).await()
            for (lbl in results) {
                val trName = strictTurkishVisualMap[lbl.text.lowercase()]
                if (trName != null) {
                    found.add(trName)
                }
            }
        } catch (_: Exception) {}
        return found.take(2).joinToString(" • ")
    }

    fun extractCompressedAacForAi(videoFile: File, maxSeconds: Int = 12): ByteArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoFile.absolutePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC || mime.contains("mp4a", ignoreCase = true)) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }
            if (audioTrackIndex == -1 || format == null) return ByteArray(0)
            extractor.selectTrack(audioTrackIndex)

            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val freqIdx = when (sampleRate) {
                96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4
                32000 -> 5; 24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9
                11025 -> 10; 8000 -> 11; else -> 4
            }
            val chanCfg = channelCount.coerceIn(1, 2)

            val out = ByteArrayOutputStream(48 * 1024)
            val readBuf = ByteBuffer.allocate(65536)
            val maxTimeUs = maxSeconds * 1_000_000L

            while (true) {
                readBuf.clear()
                val sampleSize = extractor.readSampleData(readBuf, 0)
                if (sampleSize < 0) break
                val sampleTime = extractor.sampleTime
                if (sampleTime > maxTimeUs) break

                val packetLen = sampleSize + 7
                val adts = ByteArray(7)
                val profile = 2
                adts[0] = 0xFF.toByte()
                adts[1] = 0xF9.toByte()
                adts[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
                adts[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
                adts[4] = ((packetLen and 0x7FF) shr 3).toByte()
                adts[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
                adts[6] = 0xFC.toByte()

                out.write(adts)
                val rawBytes = ByteArray(sampleSize)
                readBuf.position(0)
                readBuf.get(rawBytes, 0, sampleSize)
                out.write(rawBytes)

                if (!extractor.advance()) break
            }
            return out.toByteArray()
        } catch (_: Exception) {
            return ByteArray(0)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    fun extractPcm16kMonoForAi(videoFile: File, maxSeconds: Int = 12): ByteArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val mono16kOut = ByteArrayOutputStream(32000 * maxSeconds)
        try {
            extractor.setDataSource(videoFile.absolutePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }
            if (audioTrackIndex == -1 || format == null) return ByteArray(0)
            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return ByteArray(0)
            var pcmSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var pcmChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            try {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            } catch (_: Exception) {}

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false
            var totalProcessedTimeUs = 0L
            val maxAudioDurationUs = maxSeconds * 1_000_000L

            while (!isEOS && totalProcessedTimeUs < maxAudioDurationUs) {
                val inIndex = codec.dequeueInputBuffer(8_000)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            totalProcessedTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, totalProcessedTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }
                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 8_000)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            pcmSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            pcmChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    } else if (outIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val pcmBytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(pcmBytes)
                            val resampled = resampleTo16kMonoBytes(pcmBytes, pcmSampleRate, pcmChannels)
                            if (resampled.isNotEmpty()) {
                                mono16kOut.write(resampled)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
            return normalizeAudioGain(mono16kOut.toByteArray())
        } catch (_: Exception) {
            return ByteArray(0)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun transcribeVideoAudio(videoFile: File, voskModel: Model): String {
        val extractor = MediaExtractor()
        val transcriptParts = mutableListOf<String>()
        var codec: MediaCodec? = null
        var recognizer: Recognizer? = null

        try {
            extractor.setDataSource(videoFile.absolutePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) return ""

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return ""
            var pcmSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var pcmChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            try {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            } catch (_: Exception) {}

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false
            var totalProcessedTimeUs = 0L
            val maxAudioDurationUs = 12_000_000L

            val mono16kOut = ByteArrayOutputStream(32000 * 15)

            while (!isEOS && totalProcessedTimeUs < maxAudioDurationUs) {
                val inIndex = codec.dequeueInputBuffer(8_000)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            totalProcessedTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, totalProcessedTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 8_000)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            pcmSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            pcmChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    } else if (outIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val pcmBytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(pcmBytes)

                            val resampled = resampleTo16kMonoBytes(pcmBytes, pcmSampleRate, pcmChannels)
                            if (resampled.isNotEmpty()) {
                                mono16kOut.write(resampled)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            val all16kBytes = normalizeAudioGain(mono16kOut.toByteArray())
            if (all16kBytes.isNotEmpty()) {
                recognizer = Recognizer(voskModel, 16000f)
                val chunkSize = 8000
                var offset = 0
                while (offset < all16kBytes.size) {
                    val len = minOf(chunkSize, all16kBytes.size - offset)
                    var chunkPeak = 0
                    var i = offset
                    while (i + 1 < offset + len) {
                        val sample = (all16kBytes[i].toInt() and 0xFF) or (all16kBytes[i + 1].toInt() shl 8)
                        val amp = abs(sample)
                        if (amp > chunkPeak) chunkPeak = amp
                        i += 6
                    }
                    if (chunkPeak > 1400) {
                        val chunk = all16kBytes.copyOfRange(offset, offset + len)
                        if (recognizer.acceptWaveForm(chunk, len)) {
                            val resJson = JSONObject(recognizer.result)
                            val text = resJson.optString("text", "").trim()
                            if (text.isNotBlank()) transcriptParts.add(text)
                        }
                    }
                    offset += len
                }
                val finalJson = JSONObject(recognizer.finalResult)
                val finalText = finalJson.optString("text", "").trim()
                if (finalText.isNotBlank()) transcriptParts.add(finalText)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { recognizer?.close() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
        return autoCorrectTurkishReplik(transcriptParts.joinToString(" ").trim())
    }

    private fun resampleTo16kMonoBytes(rawPcm: ByteArray, inSampleRate: Int, channels: Int): ByteArray {
        val totalShorts = rawPcm.size / 2
        if (totalShorts == 0 || channels <= 0) return ByteArray(0)

        val frames = totalShorts / channels
        if (frames == 0) return ByteArray(0)

        val monoShorts = ShortArray(frames)
        val bb = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN)

        if (channels == 1) {
            bb.asShortBuffer().get(monoShorts)
        } else {
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) {
                    sum += bb.short.toInt()
                }
                monoShorts[i] = (sum / channels).toShort()
            }
        }

        val targetRate = 16000
        if (inSampleRate == targetRate) {
            val outBytes = ByteArray(frames * 2)
            ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(monoShorts)
            return outBytes
        }

        val ratio = inSampleRate.toDouble() / targetRate.toDouble()
        val outLen = (frames / ratio).toInt()
        if (outLen <= 0) return ByteArray(0)

        val outBytes = ByteArray(outLen * 2)
        val outBb = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outLen) {
            val srcIdx = (i * ratio).toInt().coerceIn(0, frames - 1)
            outBb.putShort(monoShorts[srcIdx])
        }
        return outBytes
    }

    private fun normalizeAudioGain(pcm16kBytes: ByteArray): ByteArray {
        val numShorts = pcm16kBytes.size / 2
        if (numShorts == 0) return pcm16kBytes

        val shorts = ShortArray(numShorts)
        ByteBuffer.wrap(pcm16kBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        var prevSample = 0
        for (i in 0 until numShorts) {
            val cur = shorts[i].toInt()
            val emphasized = (cur - (prevSample * 0.65f).toInt()).coerceIn(-32767, 32767)
            prevSample = cur
            shorts[i] = emphasized.toShort()
        }

        var maxPeak = 0
        for (s in shorts) {
            val a = abs(s.toInt())
            if (a > maxPeak) maxPeak = a
        }

        if (maxPeak in 300..16000) {
            val gain = (24000f / maxPeak.toFloat()).coerceAtMost(7.0f)
            val outBytes = ByteArray(pcm16kBytes.size)
            val outBb = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in shorts) {
                val boosted = (s.toInt() * gain).toInt().coerceIn(-32767, 32767).toShort()
                outBb.putShort(boosted)
            }
            return outBytes
        }
        return pcm16kBytes
    }
}
