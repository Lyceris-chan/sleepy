package dev.sleepy.app.testing

/**
 * The `Lclass;->member` references in a block of smali, read out with the signature each names.
 *
 * The point of reading the signature rather than only the name is that a member can exist under
 * the name a patch uses and still be unresolvable: `Log;->e(Ljava/lang/String;Ljava/lang/String;)V`
 * is a different method from `Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V`,
 * and a body that calls the wrong one dies with `NoSuchMethodError` exactly as if the name were
 * gone. Names alone are the weaker check, and the failure this file exists to catch was a name
 * that had moved to another class, so both halves have to be compared.
 *
 * A reference is one of two shapes, and which one it is decides what follows the member name:
 *
 * ```
 * Lkotlin/collections/v;->h([Ljava/lang/Object;)Ljava/util/List;    // a method
 * Lokhttp3/Request;->a:Lokhttp3/HttpUrl;                            // a field
 * ```
 *
 * The parser walks the text rather than matching it with a pattern, because a descriptor is not
 * a regular language once arrays and object types are both allowed, and a pattern that gets
 * `[Lkotlin/reflect/KClass;` wrong is a check that silently stops checking.
 */
object SmaliReferences {

    /** One reference: the class, the member, and what the member is declared as. */
    data class Reference(
        val descriptor: String,
        val member: String,
        /** For a method, `(params)return`; for a field, the type alone. */
        val signature: String,
        val isField: Boolean
    ) {
        /** How this reference is looked up in a class's declared members. */
        val lookup: String get() = if (isField) "$member:$signature" else member + signature
    }

    private val MEMBER_NAME = Regex("[A-Za-z0-9_$<>]+")
    private val CLASS_DESCRIPTOR = Regex("L[A-Za-z0-9_$/]+;")
    private const val PRIMITIVES = "VZBSCIJFD"

    /** Every `Lclass;->member` in [text], in order. */
    fun inText(text: String): List<Reference> {
        val out = ArrayList<Reference>()
        var i = 0
        while (i < text.length) {
            if (text[i] != 'L') {
                i++
                continue
            }
            val end = text.indexOf(';', i)
            if (end == -1) break
            val descriptor = text.substring(i, end + 1)
            if (!CLASS_DESCRIPTOR.matches(descriptor) || !text.startsWith("->", end + 1)) {
                i++
                continue
            }
            var j = end + 3
            val name = MEMBER_NAME.find(text.substring(j))?.takeIf { it.range.first == 0 }
            if (name == null) {
                i++
                continue
            }
            val member = name.value
            j += member.length
            when {
                j < text.length && text[j] == '(' -> {
                    val close = text.indexOf(')', j)
                    if (close == -1) break
                    val parameters = text.substring(j, close + 1)
                    val (returnType, _) = readType(text, close + 1)
                    if (returnType != null) {
                        out += Reference(
        descriptor, member, parameters + returnType, isField = false
    )
                    }
                }

                j < text.length && text[j] == ':' -> {
                    val (type, _) = readType(text, j + 1)
                    if (type != null) out += Reference(descriptor, member, type, isField = true)
                }
            }
            i = end + 1
        }
        return out
    }

    /**
     * The member names [text] declares itself, from its `.field` lines.
     *
     * A patch that adds a field names a member the build does not have yet, which is the point of
     * that patch rather than a stale reference, so those are collected and treated as present.
     */
    fun declaredFieldNames(text: String): Set<String> =
        DECLARED_FIELD.findAll(text).map { it.groupValues[1] }.toSet()

    private val DECLARED_FIELD =
        Regex("""^\s*\.field\b[^\n]*?\s([A-Za-z0-9_$]+):""", RegexOption.MULTILINE)

    /** Reads one type descriptor at [start]: `[`s, then either `L...;` or one primitive. */
    private fun readType(text: String, start: Int): Pair<String?, Int> {
        var i = start
        while (i < text.length && text[i] == '[') i++
        if (i >= text.length) return null to start
        return when {
            text[i] == 'L' -> {
                val end = text.indexOf(';', i)
                if (end == -1) null to start else text.substring(start, end + 1) to (end + 1)
            }

            text[i] in PRIMITIVES -> text.substring(start, i + 1) to (i + 1)
            else -> null to start
        }
    }
}
