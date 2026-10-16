package xyz.hlovet.portal.prototype

internal data class NativeAuditPolicyForm(
    val cleanupEnabled: Boolean,
    val businessDays: String,
    val securityDays: String,
    val serverDays: String,
)

internal fun NativeAuditPolicy.toForm() = NativeAuditPolicyForm(cleanupEnabled, businessDays.toString(), securityDays.toString(), serverDays.toString())

internal fun nativeOperationsNumber(value: String, label: String, min: Int, max: Int): Int {
    val parsed = value.takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.toIntOrNull()
    require(parsed != null && parsed in min..max) {
        nativePhrase("$label 应为 $min 到 $max 的整数。", "$label must be an integer from $min to $max.")
    }
    return parsed
}

internal fun nativeOperationsPaths(selected: Set<String>, targets: List<NativeLoadTestTarget>): List<String> {
    val allowed = targets.map { it.path }.toSet()
    require(selected.size in 1..3 && selected.all { it in allowed }) {
        nativePhrase("请选择 1 到 3 个当前允许的只读接口。", "Select 1 to 3 currently allowed read-only endpoints.")
    }
    return selected.toList()
}
