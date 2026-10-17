package com.jichi.ob.api

import android.util.Log
import com.jichi.ob.GpxToFitConverter
import com.jichi.ob.model.ActivityRecord
import com.jichi.ob.model.DataSource
import com.jichi.ob.model.UploadSupport
import com.jichi.ob.util.FileNameGenerator
import com.jichi.ob.util.FitTimeShift
import com.jichi.ob.util.GpxTimeFixer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一上传引擎（正式版精简版）
 * 仅支持上传到：Outbase（正式版定位：只保留 Outbase 单向上传，主打稳定）
 * v8.6.1: 移除开发体验版互传功能——iGPSPORT/行者/迈金/黑鸟/百锐腾/佳明/高驰/Wahoo 上传实现全部裁剪
 */
class UploadEngine(private val context: android.content.Context? = null) {

    companion object {
        private const val TAG = "UploadEngine"

        /** v8.6.5: 上传结果消息分类（抽成纯函数便于单测）——
         *  成功=含"成功"且非跳过且非"待处理"；pending=已上传但目标端"待处理/未确认入库"（Outbase异步解析），
         *  调用方不写已同步记忆、下次重试；OB端处理完返回"已存在"即记已同步，保证最终一致。 */
        internal fun classifyUploadMsg(msg: String, skipped: Boolean): UploadResult {
            val isPending = msg.contains("待处理")
            return UploadResult(
                success = !skipped && msg.contains("成功") && !isPending,
                message = msg,
                skipped = skipped,
                pending = isPending
            )
        }

        /**
         * v9.2.0: Outbase 上传前时区修正（移植开发版 v9.2.2 applyIgpTimezoneShift）
         * 按活动来源平台 +（当天/历史）读取用户设置：
         *   setting 1=+8小时：FIT → FitTimeShift.shiftForIgpsport；GPX → GpxTimeFixer.fixGpxTime(data, -8)（加8）
         *   setting 2=-8小时：FIT → FitTimeShift.shiftForIgpsportMinus；GPX → GpxTimeFixer.fixGpxTime(data, 8)（减8）
         *   setting 0=不调整（默认）：原样返回
         * 无 context（如单测）或未设置时一律不调整，不影响既有行为。
         */
        @JvmStatic
        internal fun applyTimezoneShiftForOutbase(
            context: android.content.Context?,
            data: ByteArray,
            record: ActivityRecord
        ): ByteArray {
            val ctx = context ?: return data
            // 与开发版一致的 FIT 判定（data[8]='F' && [9]='I' && [10]='T'）
            val isFit = data.size >= 12 &&
                    data[8] == 'F'.code.toByte() &&
                    data[9] == 'I'.code.toByte() &&
                    data[10] == 'T'.code.toByte()

            val key = FitTimeShift.timeShiftSettingKey(record.source, record.startTimeMs)
            val setting = try {
                ctx.getSharedPreferences("jichi_ob_prefs", android.content.Context.MODE_PRIVATE)
                    .getInt(key, 0)
            } catch (_: Exception) { 0 }
            if (setting == 0) return data

            val startMs = record.startTimeMs
            return when {
                setting == 1 -> {
                    Log.d(TAG, "iGP时区修正+8h（按平台设置） key=$key")
                    if (isFit) FitTimeShift.shiftForIgpsport(data, startMs)
                    else GpxTimeFixer.fixGpxTime(data, -8)
                }
                setting == 2 -> {
                    Log.d(TAG, "iGP时区修正-8h（按平台设置） key=$key")
                    if (isFit) FitTimeShift.shiftForIgpsportMinus(data, startMs)
                    else GpxTimeFixer.fixGpxTime(data, 8)
                }
                else -> data
            }
        }
    }

    /** v6.2.6: Outbase GPX→FIT 转换桥（Outbase官方gpx2fit库，复用WebBridge/bridge.html/gpx2fit.js） */
    private val outbaseBridge: com.jichi.ob.util.WebBridge? by lazy {
        context?.let { com.jichi.ob.util.WebBridge(it) }
    }

    private val outbaseApi = OutbaseApi()

    data class UploadResult(
        val success: Boolean,
        val targetId: String = "",
        val message: String = "",
        val skipped: Boolean = false,
        val pending: Boolean = false
        // v8.6.5: pending=true = 已上传但目标端"待处理/未确认入库"（如Outbase异步解析），
        // 调用方不写已同步记忆，下次自动重试；目标端处理完返回"已存在"即记已同步，保证最终一致
    )

    /**
     * 上传FIT文件到目标平台（正式版固定 Outbase）
     */
    suspend fun upload(
        target: DataSource,
        credential: String,
        fitData: ByteArray,
        record: ActivityRecord,
        extra: Map<String, String> = emptyMap()
    ): UploadResult = withContext(Dispatchers.IO) {
        // v8.6.1: 正式版仅允许 Outbase 目标，其余一律拒绝（互传功能已裁剪）
        if (target != DataSource.OUTBASE) {
            return@withContext UploadResult(false, message = "正式版仅支持上传到 Outbase")
        }
        val support = UploadSupport.fromDataSource(target)
        if (!support.available) {
            return@withContext UploadResult(false, message = "${target.displayName}上传功能${support.note}")
        }

        // ===== v6.3.17 时间适配（仅 Outbase 目标）=====
        // 下载端统一产出【标准UTC带Z】GPX；Outbase 走官方 gpx2fit（Date.parse带Z按UTC）→ 保持UTC
        val isGpxFile = !com.jichi.ob.GpxToFitConverter.isFit(fitData)
        // 源归一化为UTC：行者源是"北京时间标Z"，减8；黑鸟等源本就是UTC
        val utcData = if (isGpxFile && record.source == DataSource.XINGZHE) {
            try {
                val f = com.jichi.ob.util.GpxTimeFixer.fixGpxTime(fitData, 8)
                Log.d(TAG, "源归一化: 行者GPX减8→UTC，目标=${target.displayName}"); f
            } catch (e: Exception) { Log.w(TAG, "行者源归一化失败: ${e.message}"); fitData }
        } else fitData

        val uploadData = utcData

        when (target) {
            DataSource.OUTBASE -> {
                // v9.2.0: 上传前按平台时区设置修正（默认不调整，无副作用）
                val tzData = applyTimezoneShiftForOutbase(context, uploadData, record)
                uploadToOutbase(credential, tzData, record, extra)
            }
            else -> UploadResult(false, message = "正式版仅支持上传到 Outbase")
        }
    }

    // ===== Outbase 上传（已验证可用）=====
    private suspend fun uploadToOutbase(
        sessionId: String, fitData: ByteArray, record: ActivityRecord, extra: Map<String, String>
    ): UploadResult {
        return try {
            // v6.3.5/v6.3.13: Outbase上传——GPX用官方gpx2fit转FIT（与正式版一致，能正确处理时间和心率）
            // v8.2.1: Keep运动类型修复——官方gpx2fit.js 无法识别 Keep 运动类型（默认骑行），
            //         Keep 来源直接走自研转换器（自动从 GPX <name> 读 running/cycling/hiking 标记写对 sport）
            val uploadData = if (com.jichi.ob.GpxToFitConverter.isFit(fitData)) {
                fitData
            } else if (record.source == DataSource.KEEP) {
                try {
                    // v9.1.1: 优先用record.sportType转换，兜底GPX <name>检测（对齐开发版v9.1.0）
                    val f = com.jichi.ob.GpxToFitConverter.convertWithSportType(fitData, record.sportType)
                    Log.d(TAG, "Outbase Keep->FIT(自研, sportType=${record.sportType}): ${fitData.size} -> ${f.size} bytes")
                    f
                } catch (e: Exception) {
                    Log.w(TAG, "Outbase Keep自研转换失败: ${e.message}")
                    fitData
                }
            } else {
                val officialFit = try {
                    if (outbaseBridge != null) {
                        val f = outbaseBridge!!.convertGpxToFit(fitData)
                        Log.d(TAG, "Outbase GPX->FIT(官方gpx2fit): ${fitData.size} -> ${f.size} bytes")
                        f
                    } else null
                } catch (e: Exception) {
                    Log.w(TAG, "Outbase 官方gpx2fit转换失败: ${e.message}")
                    null
                }
                officialFit ?: try {
                    // v9.1.1: 优先用record.sportType转换（佳明等平台已解析），兜底GPX <name>检测（对齐开发版v9.1.0）
                    val f = com.jichi.ob.GpxToFitConverter.convertWithSportType(fitData, record.sportType)
                    Log.d(TAG, "Outbase GPX->FIT(自研兜底, sportType=${record.sportType}): ${fitData.size} -> ${f.size} bytes")
                    f
                } catch (e: Exception) {
                    Log.w(TAG, "Outbase 自研转换也失败: ${e.message}")
                    fitData
                }
            }
            val fileName = FileNameGenerator.generate(DataSource.OUTBASE, record, "fit")
            val (msg, skipped, _) = outbaseApi.upload(sessionId, null, uploadData, fileName)
            // v8.2.1: Outbase 服务端处理为异步（"待处理"），大批量秒传易触发服务端限流导致"处理失败"。
            // 每条成功后节流 400ms，摊平上传频率，降低风控概率。
            kotlinx.coroutines.delay(400)
            // v8.6.5: "待处理"不算确认成功——OB异步解析入库未知，标记 pending，调用方不写已同步记忆，
            // 下次自动重试；OB端处理完返回"已存在"即记已同步，保证最终一致。
            classifyUploadMsg(msg, skipped)
        } catch (e: Exception) {
            Log.e(TAG, "Outbase upload error", e)
            UploadResult(false, message = "Outbase上传失败: ${e.message}")
        }
    }
}
