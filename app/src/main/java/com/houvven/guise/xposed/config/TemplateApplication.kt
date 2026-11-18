package com.houvven.guise.xposed.config

import com.houvven.guise.ContextAmbient

/**
 * 记录「哪个模板应用到了哪个应用」。
 *
 * ## 为什么不继续用参数签名比对
 * 上游判断某模板是否已应用到应用 X，用的是：
 * `config.parameterSignature() == template.parameterSignature()`
 *
 * 但应用配置里可能合法地包含模板之外的字段。例如为了统一测试而设置的
 * `networkType=4`，它不存在于模板配置中，于是签名永远对不上，
 * 界面就会把「实际正在生效的模板」显示成未启用。
 *
 * ## 现在的判定
 * 应用模板时记录 {模板ID, 模板签名快照}：
 *   - 模板没被改过 → 快照与当前模板签名一致 → 判定为已应用
 *     （应用配置里多什么字段都不影响）
 *   - 模板被改动过 → 当前签名变化 → 自动判定为未应用
 *     → 界面显示为未启用，用户重新启用后才会同步新参数
 *
 * 这正好实现了「模板更新后默认视为关闭，重新启用才更新」的语义。
 */
object TemplateApplication {

    private const val PREF_NAME = "template_application"
    private const val SEP = "\n"

    private fun prefs() = ContextAmbient.getSharedPreferences(PREF_NAME)

    /** 记录 packageName 由 templateId 应用，signature 为应用时的模板签名 */
    fun record(packageName: String, templateId: String, signature: String) {
        prefs().edit().putString(packageName, templateId + SEP + signature).apply()
    }

    /** 应用被取消应用 / 关闭时清除记录 */
    fun clear(packageName: String) {
        prefs().edit().remove(packageName).apply()
    }

    /**
     * 判断 packageName 当前是否由 templateId 这个模板应用，
     * 且模板自应用后没有被修改过。
     */
    fun isApplied(
        packageName: String,
        templateId: String,
        currentTemplateSignature: String,
    ): Boolean {
        val raw = prefs().getString(packageName, null) ?: return false
        val idx = raw.indexOf(SEP)
        if (idx <= 0) return false
        val id = raw.substring(0, idx)
        val appliedSignature = raw.substring(idx + 1)
        return id == templateId && appliedSignature == currentTemplateSignature
    }
}
