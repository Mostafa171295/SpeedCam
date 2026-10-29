package com.example.speedcam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * الشاشات بتتعامل مع المحرك والكاميرا والإعدادات من خلال Core (اللي بيفضل عايش حتى لو الشاشة اتقفلت)،
 * فالقياس يكمّل في الخلفية لما الخدمة تكون مفعّلة.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val core = Core.get(app)

    val settings: StateFlow<AppSettings> = core.settings.asStateFlow()
    val health: StateFlow<Health> = core.health.asStateFlow()
    val cameraCaps: StateFlow<CameraCaps> = core.host.caps

    private val dao = core.dao
    val records: StateFlow<List<SpeedRecord>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val anomalies: StateFlow<List<AnomalyRecord>> =
        dao.observeAnomalies().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearAnomalies() = viewModelScope.launch(Dispatchers.IO) { dao.clearAnomalies() }

    val plateReader = core.plateReader
    val engine = core.engine
    val host = core.host

    fun update(f: (AppSettings) -> AppSettings) = core.update(f)

    fun setRunning(on: Boolean) = core.setRunning(on)

    fun onCameraPermission() = core.onPermission()

    fun delete(r: SpeedRecord) = viewModelScope.launch(Dispatchers.IO) { Evidence.delete(r.evidencePath); dao.delete(r) }

    /** حذف مجموعة سجلات (ومعاها لقطات الإثبات بتاعتها). بنقسمها دفعات عشان حد SQLite لعدد المتغيرات. */
    fun deleteMany(rs: List<SpeedRecord>) = viewModelScope.launch(Dispatchers.IO) {
        rs.forEach { Evidence.delete(it.evidencePath) }
        rs.chunked(500).forEach { chunk -> dao.deleteByIds(chunk.map { it.id }) }
    }

    fun clearAll() = viewModelScope.launch(Dispatchers.IO) { Evidence.clearAll(getApplication()); dao.clear() }
}
