package dev.alorebb.kopi.daemon

/** A reflected method's name and parameter types (so overload choice can be tested on the JVM). */
data class MethodSig(val name: String, val params: List<Class<*>>)

/**
 * Picks and fills hidden `IClipboard` overloads, whose parameters change between Android releases
 * (Android 16: `getPrimaryClip(String pkg, String attributionTag, int userId, int deviceId)`).
 */
object ClipboardCall {
    const val SHELL_PACKAGE = "com.android.shell"

    private val INT: Class<*> = Int::class.javaPrimitiveType!!
    private val STRING: Class<*> = String::class.java

    /** The overload of [name] with the most parameters whose types, after [leading], are only String or int; null if none. */
    fun pick(methods: List<MethodSig>, name: String, leading: List<Class<*>> = emptyList()): MethodSig? =
        methods.filter { m ->
            m.name == name &&
                m.params.size >= leading.size &&
                m.params.take(leading.size).zip(leading).all { (param, lead) -> param.isAssignableFrom(lead) } &&
                m.params.drop(leading.size).all { it == STRING || it == INT }
        }.maxByOrNull { it.params.size }

    /** [leading] + 1st String = SHELL_PACKAGE, 2nd String = null (attributionTag), 1st int = userId, 2nd int = deviceId. */
    fun args(sig: MethodSig, leading: List<Any?>, userId: Int = 0, deviceId: Int = 0): Array<Any?> {
        var strings = 0
        var ints = 0
        val rest = sig.params.drop(leading.size).map { type ->
            if (type == STRING) {
                if (strings++ == 0) SHELL_PACKAGE else null
            } else {
                when (ints++) {
                    0 -> userId
                    1 -> deviceId
                    else -> 0
                }
            }
        }
        return (leading + rest).toTypedArray()
    }
}
