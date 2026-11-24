package com.paperfeed.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.sqrt

/**
 * Offline meaning search, the same engine as Gurbani Ask: bge-small-en-v1.5 (int8 ONNX) turns a
 * paper's title + abstract, or a search, into a 384-number vector. Papers whose vectors point the
 * same way as the search's are about the same thing, even when they share no words with it.
 * Everything runs on the phone; nothing is sent anywhere.
 */
class Embedder private constructor(context: Context) {
    private val env = OrtEnvironment.getEnvironment().apply { runCatching { setTelemetry(false) } }
    private val session: OrtSession
    private val tokenizer: WordPiece

    init {
        val assets = context.assets
        tokenizer = WordPiece(assets.open("bge_vocab.txt").bufferedReader().readLines())
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        session = env.createSession(assets.open("bge_small.onnx").use { it.readBytes() }, opts)
    }

    /** Normalised embedding of [text]; searches get bge's query prefix. */
    @Synchronized
    fun embed(text: String, query: Boolean, maxTokens: Int = MAX_TOKENS): FloatArray {
        val ids = tokenizer.encode((if (query) QUERY_PREFIX else "") + text, maxTokens)
        val shape = longArrayOf(1, ids.size.toLong())
        val tensors = buildMap {
            put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape))
            put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape))
            if ("token_type_ids" in session.inputNames)
                put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape))
        }
        try {
            session.run(tensors).use { r ->
                @Suppress("UNCHECKED_CAST")
                val cls = (r[0].value as Array<Array<FloatArray>>)[0][0]
                val norm = sqrt(cls.fold(0f) { s, x -> s + x * x })
                return FloatArray(cls.size) { cls[it] / norm }
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    companion object {
        const val DIM = 384
        const val MAX_TOKENS = 160
        private const val QUERY_PREFIX = "Represent this sentence for searching relevant passages: "
        @Volatile private var instance: Embedder? = null

        /** Loads the model (about a second the first time); call off the main thread. */
        fun get(context: Context): Embedder = instance ?: synchronized(this) {
            instance ?: Embedder(context.applicationContext).also { instance = it }
        }

        /** Text that represents a paper: its title, then as much of the abstract as fits. */
        fun paperText(title: String, abstract: String?): String =
            if (abstract.isNullOrBlank()) title else "$title. ${abstract.take(1200)}"

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        fun toBytes(v: FloatArray): ByteArray =
            ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()

        fun fromBytes(b: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { buf.float }
        }
    }
}

/** BERT uncased WordPiece tokenizer (same rules as the model's tokenizer.json). */
class WordPiece(vocabLines: List<String>) {
    private val vocab = HashMap<String, Int>(vocabLines.size * 2).apply { vocabLines.forEachIndexed { i, t -> put(t, i) } }
    private val unk = vocab.getValue("[UNK]")
    private val cls = vocab.getValue("[CLS]")
    private val sep = vocab.getValue("[SEP]")

    fun encode(text: String, maxLen: Int): LongArray {
        val out = ArrayList<Int>()
        out += cls
        for (w in basic(text)) {
            out += piece(w)
            if (out.size >= maxLen - 1) break
        }
        val ids = out.take(maxLen - 1) + sep
        return LongArray(ids.size) { ids[it].toLong() }
    }

    private fun isPunct(c: Char): Boolean {
        val cp = c.code
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun isCjk(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
        cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    /** Clean, lowercase, strip accents, split on spaces and punctuation. */
    private fun basic(text: String): List<String> {
        val sb = StringBuilder()
        for (c in text) {
            val cp = c.code
            if (cp == 0 || cp == 0xFFFD) continue
            when {
                c == '\t' || c == '\n' || c == '\r' || Character.isWhitespace(c) || Character.getType(c).toByte() == Character.SPACE_SEPARATOR -> sb.append(' ')
                Character.isISOControl(c) || Character.getType(c).toByte() == Character.FORMAT -> {}
                isCjk(cp) -> sb.append(' ').append(c).append(' ')
                else -> sb.append(c)
            }
        }
        val lowered = Normalizer.normalize(sb.toString().lowercase(), Normalizer.Form.NFD)
            .filter { Character.getType(it).toByte() != Character.NON_SPACING_MARK }
        val words = ArrayList<String>()
        for (tok in lowered.split(' ')) {
            if (tok.isEmpty()) continue
            val cur = StringBuilder()
            for (c in tok) {
                if (isPunct(c)) {
                    if (cur.isNotEmpty()) { words += cur.toString(); cur.clear() }
                    words += c.toString()
                } else cur.append(c)
            }
            if (cur.isNotEmpty()) words += cur.toString()
        }
        return words
    }

    private fun piece(word: String): List<Int> {
        if (word.length > 100) return listOf(unk)
        val out = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                vocab[sub]?.let { found = it }
                if (found != null) break
                end--
            }
            if (found == null) return listOf(unk)
            out += found!!
            start = end
        }
        return out
    }
}
