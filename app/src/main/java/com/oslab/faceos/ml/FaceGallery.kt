package com.oslab.faceos.ml

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地人脸特征库（注册模板管理）。
 *
 * 存储：SharedPreferences 中的 JSON，每条 = 姓名 + 512 维归一化特征。
 * 匹配：与所有模板计算余弦相似度（= 归一化向量点积），取最大且超过阈值者。
 *
 * 用本地内存库替代数据库，够教学实验用；后续如要支持多人多样本可换 Room。
 */
class FaceGallery(context: Context) {

    data class Entry(val name: String, val embedding: FloatArray)

    data class Match(val name: String, val similarity: Float)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 内存缓存。识别是逐帧调用的，如果每帧都从 SharedPreferences 解析 JSON，
     * 会把解析时间算进“识别耗时”，并随模板数量线性变慢。
     */
    @Volatile
    private var cache: List<Entry>? = null

    @Synchronized
    fun entries(): List<Entry> {
        cache?.let { return it }
        val raw = prefs.getString(KEY_ENTRIES, null)
        if (raw == null) {
            cache = emptyList()
            return emptyList()
        }
        val list = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val embArr = obj.getJSONArray("embedding")
                val emb = FloatArray(embArr.length()) { embArr.getDouble(it).toFloat() }
                if (emb.size == EdgeFaceRecognizer.EMBEDDING_SIZE) Entry(name, emb) else null
            }
        } catch (e: Exception) {
            emptyList()
        }
        cache = list
        return list
    }

    @Synchronized
    fun size(): Int = entries().size

    @Synchronized
    fun names(): List<String> = entries().map { it.name }

    /** 注册/覆盖一个姓名的模板 */
    @Synchronized
    fun register(name: String, embedding: FloatArray) {
        val list = entries().filter { it.name != name }.toMutableList()
        list.add(Entry(name, embedding.copyOf()))
        val arr = JSONArray()
        for (e in list) {
            val obj = JSONObject()
            obj.put("name", e.name)
            obj.put("embedding", JSONArray(e.embedding.map { it.toDouble() }))
            arr.put(obj)
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
        cache = null
    }

    @Synchronized
    fun remove(name: String) {
        val list = entries().filter { it.name != name }
        cache = null
        if (list.isEmpty()) {
            prefs.edit().remove(KEY_ENTRIES).apply()
            return
        }
        val arr = JSONArray()
        for (e in list) {
            val obj = JSONObject()
            obj.put("name", e.name)
            obj.put("embedding", JSONArray(e.embedding.map { it.toDouble() }))
            arr.put(obj)
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    @Synchronized
    fun clear() {
        cache = null
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    /** 1:1 匹配：返回最相似且超过阈值的模板 */
    fun match(embedding: FloatArray, threshold: Float = Config.RECOGNITION_THRESHOLD): Match? {
        var best: Entry? = null
        var bestSim = -1f
        for (e in entries()) {
            var sim = 0f
            for (i in embedding.indices) sim += embedding[i] * e.embedding[i]
            if (sim > bestSim) {
                bestSim = sim
                best = e
            }
        }
        return if (best != null && bestSim >= threshold) Match(best.name, bestSim) else null
    }

    companion object {
        private const val PREFS = "face_gallery"
        private const val KEY_ENTRIES = "entries"
    }
}
