package sh.haven.core.local

/**
 * Heuristic "did GL actually render?" check for the `gl_smoke_test` MCP tool.
 *
 * Decides whether a captured frame is **non-uniform** (real GL content) versus a
 * **flat fill** — the blank/stale frame the windowed-GL-present bug produces
 * (`project_3d_desktop_gl_prusaslicer_test`: a blank canvas came back pure white).
 *
 * Scope: this is a whole-frame check, so it is reliable only for a **full-frame
 * GL app** (a fullscreen / cage-kiosk GL test app such as glxgears / es2gears),
 * where the GL surface IS the frame. For a windowed app on a desktop the 2D
 * chrome + background would add colour and mask a blank 3D pane, so for those
 * rely on the returned image, not this verdict. It detects "non-blank", never
 * "correct".
 */
object GlCanvasProbe {
    data class Verdict(
        val nonBlank: Boolean,
        val distinctColors: Int,
        val sampled: Int,
        val topColorFraction: Double,
    )

    /**
     * @param pixels ARGB pixels, row-major, length >= [width]*[height].
     * @param gridStep sample every Nth pixel in x and y (cheap subsample).
     * @param minDistinct distinct quantised colours (5 bits/channel) at or above
     *   which the frame counts as non-blank.
     * @param maxTopFraction if a single colour covers more than this fraction of
     *   samples, treat the frame as a flat fill regardless of distinct count
     *   (guards against a near-blank frame with a few stray pixels).
     */
    fun analyze(
        pixels: IntArray,
        width: Int,
        height: Int,
        gridStep: Int = 8,
        minDistinct: Int = 12,
        maxTopFraction: Double = 0.985,
    ): Verdict {
        require(width > 0 && height > 0) { "width/height must be positive" }
        require(pixels.size >= width * height) { "pixels too small for width*height" }
        require(gridStep >= 1) { "gridStep must be >= 1" }

        val counts = HashMap<Int, Int>()
        var sampled = 0
        var y = 0
        while (y < height) {
            var x = 0
            val row = y * width
            while (x < width) {
                // Drop alpha; quantise RGB to the top 5 bits/channel so JPEG /
                // dither noise doesn't inflate the distinct-colour count.
                val q = pixels[row + x] and 0x00F8F8F8
                counts[q] = (counts[q] ?: 0) + 1
                sampled++
                x += gridStep
            }
            y += gridStep
        }
        val distinct = counts.size
        val top = counts.values.maxOrNull() ?: 0
        val topFraction = if (sampled > 0) top.toDouble() / sampled else 1.0
        val nonBlank = distinct >= minDistinct && topFraction <= maxTopFraction
        return Verdict(nonBlank, distinct, sampled, topFraction)
    }
}
