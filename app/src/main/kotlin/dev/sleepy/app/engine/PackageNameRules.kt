package dev.sleepy.app.engine

/**
 * A reason a clone package name is invalid, together with the message that the field displays.
 *
 * The messages state the rule the name violates rather than the state of the field, because the
 * user is already looking at the field.
 */
sealed class PackageNameProblem(val message: String) {
    /** The field holds nothing but whitespace. */
    data object Blank : PackageNameProblem("Enter a package name.")

    /** The name does not fit the resource table's fixed field. */
    data object TooLong : PackageNameProblem(
        "Package names can hold at most ${PackageNameRules.MAX_LENGTH} characters. " +
            "The resource table cannot store a longer one."
    )

    /** An Android application ID needs at least one dot. */
    data object NotEnoughSegments : PackageNameProblem(
        "A package name needs at least two segments separated by dots, such as com.example.app."
    )

    /** A dot with no name beside it. */
    data object EmptySegment : PackageNameProblem("Every segment needs a name between the dots.")

    /** A segment that does not begin with a letter. */
    data object SegmentStartsWrong : PackageNameProblem("Every segment starts with a letter.")

    /** A character that a package segment cannot contain. */
    data object IllegalCharacter : PackageNameProblem(
        "Package segments hold only letters, digits and underscores."
    )

    /** A segment that is a Java keyword or reserved word. */
    data class ReservedWord(val word: String) : PackageNameProblem(
        "'$word' is a Java keyword, so it cannot name a package segment."
    )

    /**
     * The name that the build already uses, which replaces the original installation instead
     * of installing alongside it.
     */
    data object SameAsOriginal : PackageNameProblem(
        "This is the package name the build already uses. " +
            "Choose another name to install alongside it."
    )
}

/**
 * The rules a clone package name has to meet before a build can start.
 *
 * A name is written into the manifest's `package` attribute and into the resource table's package
 * chunk, and the platform resolves both against the rules that follow: the segments of a Java package
 * name, the length that the table's fixed field can store, and the name of the build being
 * cloned. A name that breaks one of them is rejected here rather than by a later step of a run
 * that has already downloaded an APK.
 */
object PackageNameRules {

    /**
     * The longest package name that the resource table's package field can store.
     *
     * The field is 128 UTF-16 code units including its terminator, so a name of this length or
     * less leaves room for one; [ResourceTableMerger.renamePackage] rejects anything longer.
     */
    const val MAX_LENGTH = ResourceTableMerger.PACKAGE_NAME_MAX_LENGTH

    /** An application ID is a package name, so it needs at least one segment separator. */
    private const val MIN_SEGMENTS = 2

    /**
     * The words Java reserves, so a segment cannot be one.
     *
     * A package name is a Java package name to every tool that reads it, and a segment the language
     * reserves cannot name one. The list is the JLS's keyword list plus the three literals and the
     * underscore, which Java 9 made a keyword.
     */
    private val JAVA_RESERVED_WORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
        "null", "_"
    )

    /**
     * The first rule [name] breaks as a replacement for [originalPackageName], or null when it
     * breaks none.
     *
     * [originalPackageName] is optional: a caller that has no build can still check the shape of
     * the name, and a caller that has one also gets the equality check.
     */
    fun validate(name: String, originalPackageName: String? = null): PackageNameProblem? {
        if (name.isBlank()) return PackageNameProblem.Blank
        if (name.length > MAX_LENGTH) return PackageNameProblem.TooLong

        val segments = name.split('.')
        if (segments.size < MIN_SEGMENTS) return PackageNameProblem.NotEnoughSegments

        for (segment in segments) {
            if (segment.isEmpty()) return PackageNameProblem.EmptySegment
            if (!segment.first().isAsciiLetter()) return PackageNameProblem.SegmentStartsWrong
            if (segment.any { !it.isAsciiLetter() && !it.isAsciiDigit() && it != '_' }) {
                return PackageNameProblem.IllegalCharacter
            }
            if (segment in JAVA_RESERVED_WORDS) return PackageNameProblem.ReservedWord(segment)
        }

        if (originalPackageName != null && name == originalPackageName) {
            return PackageNameProblem.SameAsOriginal
        }
        return null
    }

    /** The segment characters the platform accepts are ASCII, so neither is `isLetterOrDigit`. */
    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
