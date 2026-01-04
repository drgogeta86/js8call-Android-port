package com.js8call.core

/**
 * Provides Hamlib rig model metadata via JNI for settings UI.
 */
object HamlibRigCatalog {
    init {
        System.loadLibrary("js8core-jni")
    }

    data class RigModel(val id: String, val label: String)

    fun listRigModels(): List<RigModel> {
        val raw = nativeListRigModels()
        if (raw.isEmpty()) return emptyList()

        val models = ArrayList<RigModel>(raw.size)
        for (entry in raw) {
            val parts = entry.split('|', limit = 2)
            if (parts.size != 2) continue
            val id = parts[0].trim()
            val label = parts[1].trim()
            if (id.isEmpty() || label.isEmpty()) continue
            models.add(RigModel(id, label))
        }
        return models
    }

    private external fun nativeListRigModels(): Array<String>
}
