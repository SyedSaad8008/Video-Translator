package com.example.videotranslator.models

import android.content.Context
import android.util.Log
import com.example.videotranslator.model.ModelInfo
import com.example.videotranslator.model.ModelStatus
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ModelManager"

/**
 * Manages the lifecycle, installation checks, automatic background download,
 * storage provisioning, and lazy memory loading of all on-device AI models.
 */
class ModelManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val downloader = ModelDownloader(context)
    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _modelStatuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    val modelStatuses: StateFlow<Map<String, ModelStatus>> = _modelStatuses.asStateFlow()

    private val _isAllReady = MutableStateFlow(false)
    val isAllReady: StateFlow<Boolean> = _isAllReady.asStateFlow()

    init {
        refreshStatuses()
        autoInitializeAllModels()
    }

    /**
     * Automatically extracts bundled assets and downloads any missing on-device models
     * in the background upon application startup.
     */
    fun autoInitializeAllModels() {
        scope.launch(Dispatchers.IO) {
            DiagnosticLogger.log(TAG, "Starting automatic on-device AI model initialization & storage check…")
            modelsDir.mkdirs()

            // 1. Extract any bundled assets first
            for (model in ModelRegistry.ALL_MODELS) {
                if (model.isBundledInAssets) {
                    extractAssetIfNeeded(model)
                }
            }

            // 2. Automatically download missing models in the background
            for (model in ModelRegistry.ALL_MODELS) {
                val file = getModelFile(model)
                if (!file.exists() || file.length() == 0L) {
                    if (downloader.hasSufficientStorage(model.sizeBytes)) {
                        DiagnosticLogger.log(TAG, "Auto-downloading required model in background: ${model.name}…")
                        try {
                            downloader.downloadModel(
                                modelInfo = model,
                                targetFile = file,
                                onProgress = { progress -> updateStatus(model.id, ModelStatus.Downloading(progress)) }
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Background auto-download notice for ${model.name}: ${e.message}")
                        }
                    }
                }
            }

            refreshStatuses()
            _isAllReady.value = true
            DiagnosticLogger.log(TAG, "Automatic on-device AI model initialization complete ✓")
        }
    }

    /**
     * Refreshes status for all registered models.
     */
    fun refreshStatuses() {
        val statusMap = mutableMapOf<String, ModelStatus>()
        for (model in ModelRegistry.ALL_MODELS) {
            val file = getModelFile(model)
            statusMap[model.id] = when {
                file.exists() && file.length() > 0 -> ModelStatus.Installed
                model.isBundledInAssets && isAssetAvailable(model.fileName) -> ModelStatus.Installed
                else -> ModelStatus.NotInstalled
            }
        }
        _modelStatuses.value = statusMap
    }

    fun isModelInstalled(modelId: String): Boolean {
        val model = ModelRegistry.getModelById(modelId) ?: return false
        val file = getModelFile(model)
        if (file.exists() && file.length() > 0) return true
        if (model.isBundledInAssets && isAssetAvailable(model.fileName)) return true
        return false
    }

    fun getModelFile(model: ModelInfo): File {
        return File(modelsDir, model.fileName)
    }

    fun getModelFile(modelId: String): File? {
        val model = ModelRegistry.getModelById(modelId) ?: return null
        return getModelFile(model)
    }

    /**
     * Deletes a model file from storage to free disk space.
     */
    fun deleteModel(modelId: String): Boolean {
        val model = ModelRegistry.getModelById(modelId) ?: return false
        val file = getModelFile(model)
        val deleted = if (file.exists()) file.delete() else true
        DiagnosticLogger.log(TAG, "Deleted model ${model.name}: $deleted")
        refreshStatuses()
        return deleted
    }

    /**
     * Extracts a bundled asset into the models directory if present.
     */
    suspend fun extractAssetIfNeeded(modelInfo: ModelInfo): File? = withContext(Dispatchers.IO) {
        val targetFile = getModelFile(modelInfo)
        if (targetFile.exists() && targetFile.length() > 0) return@withContext targetFile

        if (isAssetAvailable(modelInfo.fileName)) {
            try {
                DiagnosticLogger.log(TAG, "Extracting bundled asset: ${modelInfo.fileName} → ${targetFile.name}")
                context.assets.open(modelInfo.fileName).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                refreshStatuses()
                return@withContext targetFile
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract asset ${modelInfo.fileName}: ${e.message}")
            }
        }
        null
    }

    private fun isAssetAvailable(assetName: String): Boolean {
        return try {
            context.assets.list("")?.contains(assetName) == true
        } catch (e: Exception) {
            false
        }
    }

    private fun updateStatus(modelId: String, status: ModelStatus) {
        val map = _modelStatuses.value.toMutableMap()
        map[modelId] = status
        _modelStatuses.value = map
    }
}
