package dev.sleepy.app.engine

/**
 * Locates a Hermes bytecode function's body and length inside an HBC bundle.
 *
 * Patching a JavaScript function on-device means writing new bytecode over the old, which
 * requires knowing exactly where that function's bytecode starts and how long it is. Hermes
 * stores both in a function header table whose position and entry packing are **not**
 * documented and change between bytecode versions; there is no field in the header that
 * points at it directly.
 *
 * ## Why this refuses to guess
 *
 * An earlier implementation assumed the table began at byte 128 with a 12-byte stride and a
 * particular bit packing. Measured against the real Discord 348.5 bundle that assumption is
 * false: all fourteen target functions resolved to the table's *overflow* branch, which then
 * read a body offset and length out of unrelated bytes and used them to overwrite roughly
 * 3 KB of live JavaScript bytecode at effectively arbitrary positions — after recomputing
 * the SHA-1 footer, so Hermes happily loaded the corrupted bundle and the app died on
 * launch.
 *
 * The failure was silent because nothing checked that the located bytes were really the
 * target function. [validateContiguity] exists so that a future layout cannot be trusted on
 * its shape alone: Hermes lays function bodies out back to back, so a correct table must
 * reproduce `offset[i] + size[i] == offset[i + 1]` across the whole bundle. A layout that
 * does not satisfy that is wrong, however plausible its arithmetic looks.
 *
 * Until a layout has been verified this way against a real bundle, [locate] returns `null`
 * and the patcher writes nothing. A skipped patch is visible and harmless; a misplaced write
 * corrupts the bundle.
 */
object HermesFunctionTable {

    /** Where a function's bytecode lives, and how many bytes of it there are. */
    data class FunctionLocation(
        val bodyOffset: Int,
        val bytecodeSize: Int
    )

    /**
     * Returns the location of [functionId] in [bundleBytes], or `null` when the bundle's
     * function table layout has not been verified.
     */
    fun locate(bundleBytes: ByteArray, functionId: Int): FunctionLocation? {
        // No verified layout exists yet, so there is nothing safe to return. See the class
        // documentation for what a layout must satisfy before it can be plugged in here.
        return null
    }

    /**
     * Checks a candidate layout against the bundle's own structure.
     *
     * Hermes stores function bodies contiguously and in function-id order, so a layout is
     * only credible if consecutive entries describe adjacent regions. [sample] entries are
     * checked; any single violation disproves the layout.
     *
     * @param locations function id to the location a candidate layout produced for it.
     */
    fun validateContiguity(locations: Map<Int, FunctionLocation>, sample: Int = 512): Boolean {
        val ids = locations.keys.sorted().take(sample)
        if (ids.size < 2) return false
        for (i in 0 until ids.size - 1) {
            val current = locations[ids[i]] ?: return false
            val next = locations[ids[i + 1]] ?: return false
            if (current.bodyOffset + current.bytecodeSize != next.bodyOffset) return false
        }
        return true
    }
}
