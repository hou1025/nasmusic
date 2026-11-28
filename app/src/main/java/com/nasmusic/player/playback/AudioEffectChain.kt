package com.nasmusic.player.playback

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import androidx.annotation.RequiresApi
import com.nasmusic.player.core.SoundEffectState
import com.nasmusic.player.core.SoundMode
import com.nasmusic.player.core.ViperModules

/**
 * 音效引擎：把一组 Android 原生 AudioEffect 附加到 ExoPlayer 的 audioSessionId，
 * 对本地 / NAS / 在线音源统一生效（音频输出级处理，与音源协议无关）。
 *
 * 链路顺序（与蝰蛇音效的功能模块对应）：
 * 1. Equalizer      —— 10 段 IIR 均衡器（含"频谱扩展"对高频两段的提升）
 * 2. BassBoost      —— 动态低音
 * 3. Virtualizer    —— 场环绕
 * 4. PresetReverb   —— 数字混响（房间/大厅模拟）
 * 5. LoudnessEnhancer —— 回放增益（稳定歌曲间音量）
 * 6. DynamicsProcessing —— 限幅器（API 28+，防止破音）
 *
 * 说明：ViPER4Android 是系统级驱动（需 root），第三方 App 无法调用其私有引擎；
 * 本类用原生 API 复刻其可调模块，并提供"检测已装 V4A"提示用户避免叠加。
 * 所有效果创建均 try/catch：设备不支持时静默降级，绝不影响播放。
 */
class AudioEffectChain(private val context: Context) {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var reverb: PresetReverb? = null
    private var loudness: LoudnessEnhancer? = null
    private var limiter: Any? = null // DynamicsProcessing（API 28+），用 Any 避免低版本类引用问题

    private var attachedSession = -1
    private var lastState = SoundEffectState()

    /** 各效果器是否在当前设备上创建成功，UI 据此提示"设备不支持"的项。 */
    data class Availability(
        val equalizer: Boolean = false,
        val bass: Boolean = false,
        val clarityViaEq: Boolean = false,
        val surround: Boolean = false,
        val reverb: Boolean = false,
        val playbackGain: Boolean = false,
        val limiter: Boolean = false,
        val spectral: Boolean = false
    )

    var availability: Availability = Availability()
        private set

    /** 检测是否安装了 ViPER4Android（用于提示用户关闭其一，避免双重叠加）。 */
    fun isViperInstalled(): Boolean {
        val packages = listOf(
            "com.viperas.viper4android",
            "com.viperas.viper4android.fx",
            "com.viperas.v4a",
            "com.viperas.viper4android.googleplay",
            "com.viperas.viper4android.legacy"
        )
        val pm = context.packageManager
        return packages.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    /**
     * 附加音效链到指定 audio session。ExoPlayer 的 sessionId 可能在 prepare 后才分配，
     * 因此 PlaybackService 在 onAudioSessionIdChanged 里调用本方法。
     */
    fun attach(sessionId: Int, state: SoundEffectState) {
        // 无效 sessionId（如切轨时新 session 尚未分配）：先释放上一轮 session 的效果器，
        // 否则旧链会残留并与后续新链叠加。release() 本身幂等，重复调用安全。
        if (sessionId <= 0) {
            release()
            return
        }
        // 原生直通模式：一个效果器都不创建。旧实现即使 OFF 也会 new 出 6 个
        // AudioEffect（Binder 跨进程调用 + 音频图重连），既阻塞线程又强制关闭
        // ExoPlayer 的音频直通路径，是本地音乐也卡的系统级根因之一。
        if (state.mode == SoundMode.OFF) {
            release()
            return
        }
        // 同一 session 只更新参数，不重建效果器。
        if (sessionId == attachedSession) {
            apply(state)
            return
        }
        release()
        attachedSession = sessionId
        lastState = state

        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        reverb = runCatching { PresetReverb(0, sessionId) }.getOrNull()
        loudness = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            limiter = runCatching { createLimiter(sessionId) }.getOrNull()
        }

        availability = Availability(
            equalizer = equalizer != null,
            bass = bassBoost != null,
            clarityViaEq = equalizer != null,
            surround = virtualizer != null,
            reverb = reverb != null,
            playbackGain = loudness != null,
            limiter = limiter != null,
            spectral = equalizer != null
        )
        apply(state)
    }

    /** 实时应用配置：模式切换 / 调参 / 换预设后立即生效。 */
    fun apply(state: SoundEffectState) {
        lastState = state
        val on = state.mode != SoundMode.OFF
        // 从音效切回原生直通时，必须彻底释放效果器实例，否则音频仍走效果链处理路径
        if (!on) {
            release()
            lastState = state
            return
        }
        applyEqualizer(state, on)
        applyBass(state, on)
        applySurround(state, on)
        applyReverb(state, on)
        applyLoudness(state, on)
        applyLimiter(on && state.mode == SoundMode.VIPER &&
                ViperModules.enabled(state.viperModules, ViperModules.LIMITER))
    }

    private fun applyEqualizer(state: SoundEffectState, on: Boolean) {
        val eq = equalizer ?: return
        runCatching {
            if (!on || (state.mode == SoundMode.VIPER &&
                        !ViperModules.enabled(state.viperModules, ViperModules.EQUALIZER))) {
                eq.enabled = false
                return@runCatching
            }
            val bands = eq.numberOfBands.toInt().coerceAtLeast(1)
            val min = eq.bandLevelRange[0].toInt()
            val max = eq.bandLevelRange[1].toInt()
            val base = state.effectiveEqDb
            // ViPER Clarity：提升中高频段（索引 3~6，约 500Hz~8kHz）的人声/乐器清晰度。
            val clarityDb = if (state.mode == SoundMode.VIPER &&
                ViperModules.enabled(state.viperModules, ViperModules.CLARITY)) {
                (state.effectiveClarity / 250.0).toInt().coerceIn(0, 5)
            } else if (state.mode == SoundMode.CAR) {
                (state.effectiveClarity / 300.0).toInt().coerceIn(0, 5)
            } else 0

            // 逐段设置增益（单位 millibel，1dB = 100mB）
            for (i in 0 until bands) {
                var db = base.getOrNull(i) ?: 0
                if (i in 3..6) db += clarityDb
                eq.setBandLevel(i.toShort(), (db * 100).coerceIn(min, max).toShort())
            }
            eq.enabled = true
        }
    }

    private fun applyBass(state: SoundEffectState, on: Boolean) {
        val bb = bassBoost ?: return
        runCatching {
            val allowed = on && (state.mode == SoundMode.CAR ||
                    ViperModules.enabled(state.viperModules, ViperModules.DYNAMIC_BASS))
            if (!allowed || state.effectiveBass == 0) {
                bb.enabled = false
                return@runCatching
            }
            bb.setStrength(state.effectiveBass.coerceIn(0, SoundEffectState.STRENGTH_MAX).toShort())
            bb.enabled = true
        }
    }

    private fun applySurround(state: SoundEffectState, on: Boolean) {
        val v = virtualizer ?: return
        runCatching {
            val allowed = on && (state.mode == SoundMode.CAR ||
                    ViperModules.enabled(state.viperModules, ViperModules.SURROUND))
            if (!allowed || state.effectiveSurround == 0) {
                v.enabled = false
                return@runCatching
            }
            v.setStrength(state.effectiveSurround.coerceIn(0, SoundEffectState.STRENGTH_MAX).toShort())
            v.enabled = true
        }
    }

    private fun applyReverb(state: SoundEffectState, on: Boolean) {
        val r = reverb ?: return
        runCatching {
            val allowed = on && (state.mode == SoundMode.VIPER &&
                    ViperModules.enabled(state.viperModules, ViperModules.REVERB))
            if (!allowed || state.effectiveReverb == 0.toShort()) {
                r.enabled = false
                return@runCatching
            }
            r.preset = state.effectiveReverb
            r.enabled = true
        }
    }

    private fun applyLoudness(state: SoundEffectState, on: Boolean) {
        val l = loudness ?: return
        runCatching {
            val allowed = on && (state.mode == SoundMode.CAR ||
                    ViperModules.enabled(state.viperModules, ViperModules.PLAYBACK_GAIN))
            if (!allowed || state.effectiveLoudness == 0) {
                l.enabled = false
                return@runCatching
            }
            l.setTargetGain(state.effectiveLoudness.coerceIn(0, SoundEffectState.LOUDNESS_MAX_MB))
            l.enabled = true
        }
    }

    private fun applyLimiter(on: Boolean) {
        val dp = limiter ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching { setLimiterEnabled(dp as android.media.audiofx.DynamicsProcessing, on) }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createLimiter(sessionId: Int): android.media.audiofx.DynamicsProcessing {
        val dp = android.media.audiofx.DynamicsProcessing(
            0, sessionId,
            android.media.audiofx.DynamicsProcessing.Config.Builder(
                android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                1,           // channelCount
                false, 0,    // preEq disabled, 0 bands
                false, 0,    // mbc disabled, 0 bands
                false, 0,    // postEq disabled, 0 bands
                true         // limiter enabled
            ).build()
        )
        runCatching {
            // 为通道显式设置限幅器参数（阈值 -1.5dB，attack/release 取默认值）
            val limiter = android.media.audiofx.DynamicsProcessing.Limiter(
                true, false, 0, 5f, 100f, 1f, -1.5f, 0f
            )
            dp.setLimiterAllChannelsTo(limiter)
            dp.enabled = true
        }
        return dp
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun setLimiterEnabled(dp: android.media.audiofx.DynamicsProcessing, on: Boolean) {
        dp.enabled = on
        runCatching { dp.getChannelByChannelIndex(0).limiter?.isEnabled = on }
    }

    /** 释放全部效果器（Service 销毁或 session 变化时调用）。 */
    fun release() {
        listOf(equalizer, bassBoost, virtualizer, reverb, loudness, limiter).forEach { effect ->
            runCatching {
                when (effect) {
                    is AudioEffect -> effect.release()
                    is android.media.audiofx.DynamicsProcessing -> effect.release()
                }
            }
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        reverb = null
        loudness = null
        limiter = null
        attachedSession = -1
        availability = Availability()
    }

    /** 当前附加的 session，用于调试。 */
    fun currentSession(): Int = attachedSession

    /** 最近一次应用的配置，用于调试。 */
    fun lastAppliedState(): SoundEffectState = lastState
}
