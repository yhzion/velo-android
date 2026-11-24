package io.github.yhzion.velo.ui

internal enum class SelectionEnd { Start, End }

/** Native endpoints are cell edges. Android grips can be grabbed anywhere in a 48dp target;
 * dragging preserves that offset and reports a point just inside the corresponding cell. */
internal class SelectionHandles {
    private lateinit var edges: IntArray
    private var width = 0
    private var height = 0
    var radius = 0f
        private set
    private var dx = 0f
    private var dy = 0f

    fun place(bounds: IntArray, w: Int, h: Int, density: Float) {
        edges = bounds
        width = w
        height = h
        radius = 8f * density
    }

    fun edgeX(end: SelectionEnd) = edges[if (end == SelectionEnd.Start) 0 else 2].toFloat()
    fun edgeY(end: SelectionEnd) = edges[if (end == SelectionEnd.Start) 1 else 3].toFloat()
    private fun inside(value: Float, extent: Int): Float {
        val inset = radius.coerceAtMost(extent / 2f)
        return value.coerceIn(inset, extent - inset)
    }
    fun knobX(end: SelectionEnd) = inside(edgeX(end) + if (end == SelectionEnd.Start) -radius else radius, width)
    fun knobY(end: SelectionEnd) = inside(edgeY(end) + radius, height)

    fun hit(x: Float, y: Float): SelectionEnd? {
        fun distance(end: SelectionEnd): Float {
            val xDiff = x - knobX(end)
            val yDiff = y - knobY(end)
            return xDiff * xDiff + yDiff * yDiff
        }
        val start = distance(SelectionEnd.Start)
        val end = distance(SelectionEnd.End)
        if (minOf(start, end) > radius * radius * 9) return null
        return if (start <= end) SelectionEnd.Start else SelectionEnd.End
    }

    fun grab(end: SelectionEnd, x: Float, y: Float) {
        dx = edgeX(end) - x + if (end == SelectionEnd.Start) 1f else -1f
        dy = edgeY(end) - y - 1f
    }
    fun cellX(x: Float) = x + dx
    fun cellY(y: Float) = y + dy
}
