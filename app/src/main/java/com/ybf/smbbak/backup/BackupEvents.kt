package com.ybf.smbbak.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 备份运行状态（内存单例），供 UI、前台服务、Worker 共享。
 * busy 标记保证同一时刻只有一个备份任务在跑。
 */
object BackupEvents {

    data class Snapshot(
        val running: Boolean = false,
        val startedAt: Long = 0L,
        val phase: String = "",          // 阶段描述（直接显示）
        val sourceLabel: String = "",
        val currentName: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val uploaded: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val bytes: Long = 0L,            // 已传输字节（含当前文件实时进度）
        val speedBps: Long = 0L          // 实时上传速度（字节/秒）
    ) {
        val percent: Int get() = if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val busy = AtomicBoolean(false)
    val isBusy: Boolean get() = busy.get()

    @Volatile
    private var cancelRequested = false
    val isCancelled: Boolean get() = cancelRequested

    @Volatile
    private var pauseRequested = false
    val isPauseRequested: Boolean get() = pauseRequested

    /** 尝试开始一次新任务；已在跑则返回 false */
    fun begin(): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        cancelRequested = false
        pauseRequested = false
        _state.value = Snapshot(running = true, startedAt = System.currentTimeMillis())
        return true
    }

    fun finish() {
        busy.set(false)
        _state.update { it.copy(running = false) }
    }

    fun requestCancel() {
        cancelRequested = true
    }

    /** 暂停：引擎会在当前文件传输处尽快停止并把剩余任务落盘 */
    fun requestPause() {
        pauseRequested = true
    }

    fun setPhase(phase: String) {
        _state.update { it.copy(phase = phase) }
    }

    fun setSource(label: String) {
        _state.update { it.copy(sourceLabel = label, done = 0, total = 0) }
    }

    fun sourceProgress(label: String, done: Int, total: Int) {
        _state.update { it.copy(sourceLabel = label, done = done, total = total) }
    }

    fun addUploaded() {
        _state.update { it.copy(uploaded = it.uploaded + 1) }
    }

    fun addSkipped() {
        _state.update { it.copy(skipped = it.skipped + 1) }
    }

    fun addFailed() {
        _state.update { it.copy(failed = it.failed + 1) }
    }

    /** 更新实时字节与上传速度（上传过程中按节拍调用） */
    fun transfer(bytes: Long, speedBps: Long) {
        _state.update { it.copy(bytes = bytes, speedBps = speedBps) }
    }

    fun setCurrent(name: String) {
        _state.update { it.copy(currentName = name) }
    }
}
