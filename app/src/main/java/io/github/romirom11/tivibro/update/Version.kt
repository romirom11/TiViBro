package io.github.romirom11.tivibro.update

/** A release version, X.Y.Z. Tags may carry a leading `v`; missing parts are 0; anything after the digits of a part
 *  (`1.2.3-beta`) is ignored. */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val SHAPE = Regex("""^[vV]?\d+(\.\d+){0,2}([-+.].*)?$""")

        fun parse(s: String?): Version? {
            val t = s?.trim() ?: return null
            if (!SHAPE.matches(t)) return null
            val p = t.removePrefix("v").removePrefix("V").split('.', '-', '+').take(3)
                .map { it.takeWhile(Char::isDigit).toIntOrNull() }
            val nums = p.takeWhile { it != null }.map { it!! }
            if (nums.isEmpty()) return null
            return Version(nums[0], nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
        }

        /** Whether the release [tag] is newer than the installed [current] version name. */
        fun isNewer(tag: String?, current: String?): Boolean {
            val t = parse(tag) ?: return false
            val c = parse(current) ?: return true
            return t > c
        }
    }
}
