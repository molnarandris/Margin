package io.github.molnarandris.margin.ui.pdfviewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntSize
import kotlin.math.sqrt

internal data class ScribbleResult(val isScribble: Boolean, val reversalPoints: List<Offset>)

internal fun isScribble(points: List<Offset>): ScribbleResult {
    val no = ScribbleResult(false, emptyList())
    if (points.size < 3) return no
    var totalLength = 0f
    for (i in 1 until points.size) {
        val dx = points[i].x - points[i-1].x; val dy = points[i].y - points[i-1].y
        totalLength += sqrt(dx * dx + dy * dy)
    }
    if (totalLength < 20f) return no
    // Downsample: keep only points >= 8px apart to eliminate jitter from slow drawing
    val ds = mutableListOf(points[0])
    for (pt in points) {
        val last = ds.last(); val dx = pt.x - last.x; val dy = pt.y - last.y
        if (sqrt(dx * dx + dy * dy) >= 8f) ds.add(pt)
    }
    if (ds.size < 3) return no
    val reversalPts = mutableListOf<Offset>()
    for (i in 1 until ds.size - 1) {
        val dx1 = ds[i].x - ds[i-1].x; val dy1 = ds[i].y - ds[i-1].y
        val dx2 = ds[i+1].x - ds[i].x;  val dy2 = ds[i+1].y - ds[i].y
        val dot = dx1 * dx2 + dy1 * dy2
        if (dot < 0f && dot * dot > 0.0302f * (dx1*dx1 + dy1*dy1) * (dx2*dx2 + dy2*dy2))
            reversalPts.add(ds[i])
    }
    return if (reversalPts.size >= 4) ScribbleResult(true, reversalPts) else no
}

internal fun rdpSimplify(points: List<Offset>, epsilon: Float): List<Offset> {
    if (points.size < 3) return points
    val first = points.first(); val last = points.last()
    val dx = last.x - first.x; val dy = last.y - first.y
    val lenSq = dx * dx + dy * dy
    var maxDist = 0f; var maxIdx = 0
    for (i in 1 until points.size - 1) {
        val dist = if (lenSq == 0f) {
            val ex = points[i].x - first.x; val ey = points[i].y - first.y
            sqrt(ex * ex + ey * ey)
        } else {
            val t = ((points[i].x - first.x) * dx + (points[i].y - first.y) * dy) / lenSq
            val cx = first.x + t * dx; val cy = first.y + t * dy
            val ex = points[i].x - cx; val ey = points[i].y - cy
            sqrt(ex * ex + ey * ey)
        }
        if (dist > maxDist) { maxDist = dist; maxIdx = i }
    }
    return if (maxDist > epsilon) {
        val left = rdpSimplify(points.subList(0, maxIdx + 1), epsilon)
        val right = rdpSimplify(points.subList(maxIdx, points.size), epsilon)
        left.dropLast(1) + right
    } else {
        listOf(first, last)
    }
}

// Build a smooth Catmull-Rom spline as a Compose Path. Points must already be in pixel space.
internal fun catmullRomPath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size < 3) {
        pts.drop(1).forEach { path.lineTo(it.x, it.y) }
        return path
    }
    for (i in 0 until pts.size - 1) {
        val prev = if (i == 0) i else i - 1
        val next2 = if (i + 2 >= pts.size) pts.size - 1 else i + 2
        val p = pts[i]; val q = pts[i + 1]
        val pp = pts[prev]; val nn = pts[next2]
        val cp1x = p.x + (q.x - pp.x) / 6f; val cp1y = p.y + (q.y - pp.y) / 6f
        val cp2x = q.x - (nn.x - p.x) / 6f; val cp2y = q.y - (nn.y - p.y) / 6f
        path.cubicTo(cp1x, cp1y, cp2x, cp2y, q.x, q.y)
    }
    return path
}

internal fun segmentsIntersect(a1: Offset, a2: Offset, b1: Offset, b2: Offset): Boolean {
    fun cross(o: Offset, a: Offset, b: Offset) =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val d1 = cross(b1, b2, a1); val d2 = cross(b1, b2, a2)
    val d3 = cross(a1, a2, b1); val d4 = cross(a1, a2, b2)
    return (d1 > 0 && d2 < 0 || d1 < 0 && d2 > 0) &&
           (d3 > 0 && d4 < 0 || d3 < 0 && d4 > 0)
}

internal fun strokeIntersectsScribble(
    strokePts: List<Offset>, scribbleNorm: List<Offset>, minCrossings: Int = 3
): Boolean {
    var count = 0
    for (i in 0 until strokePts.size - 1)
        for (j in 0 until scribbleNorm.size - 1)
            if (segmentsIntersect(strokePts[i], strokePts[i+1], scribbleNorm[j], scribbleNorm[j+1]) &&
                    ++count >= minCrossings) return true
    return false
}

internal fun pointToSegmentDist(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x; val dy = b.y - a.y
    if (dx == 0f && dy == 0f) {
        val ex = p.x - a.x; val ey = p.y - a.y
        return sqrt(ex * ex + ey * ey)
    }
    val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)
    val cx = a.x + t.coerceIn(0f, 1f) * dx
    val cy = a.y + t.coerceIn(0f, 1f) * dy
    val ex = p.x - cx; val ey = p.y - cy
    return sqrt(ex * ex + ey * ey)
}

internal fun strokeNearScribble(
    strokeNorm: List<Offset>, scribblePx: List<Offset>, pageSize: IntSize,
    thresholdPx: Float, minFraction: Float = 0.3f
): Boolean {
    val strokePx = strokeNorm.map { Offset(it.x * pageSize.width, it.y * pageSize.height) }
    // Direction 1: fraction of target-stroke points near any scribble segment
    if (strokePx.size >= 2) {
        var match = 0
        for (pt in strokePx)
            for (j in 0 until scribblePx.size - 1)
                if (pointToSegmentDist(pt, scribblePx[j], scribblePx[j + 1]) <= thresholdPx) {
                    match++; break
                }
        if (match.toFloat() / strokePx.size >= minFraction) return true
    }
    // Direction 2: fraction of scribble points near any target-stroke segment
    if (scribblePx.size >= 2) {
        var rev = 0
        for (pt in scribblePx)
            for (j in 0 until strokePx.size - 1)
                if (pointToSegmentDist(pt, strokePx[j], strokePx[j + 1]) <= thresholdPx) {
                    rev++; break
                }
        if (rev.toFloat() / scribblePx.size >= minFraction) return true
    }
    return false
}

internal fun convexHull(pts: List<Offset>): List<Offset> {
    val points = pts.distinct()
    if (points.size < 3) return points
    var current = points.minByOrNull { it.x }!!
    val hull = mutableListOf<Offset>()
    do {
        hull.add(current)
        var next = points[0]
        for (c in points) {
            if (next == current) { next = c; continue }
            val cross = (next.x - current.x) * (c.y - current.y) -
                        (next.y - current.y) * (c.x - current.x)
            if (cross < 0f) next = c
            else if (cross == 0f) {
                val d1 = (next.x - current.x).let { it * it } + (next.y - current.y).let { it * it }
                val d2 = (c.x - current.x).let { it * it } + (c.y - current.y).let { it * it }
                if (d2 > d1) next = c
            }
        }
        current = next
    } while (current != hull[0] && hull.size <= points.size)
    return hull
}

internal fun isApproxRectangle(points: List<Offset>): Boolean {
    if (points.size < 10) return false
    val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
    val dx = maxX - minX; val dy = maxY - minY
    val bboxDiag = sqrt(dx * dx + dy * dy)
    val simplified = rdpSimplify(points, epsilon = bboxDiag * 0.04f)
    if (simplified.size < 4) return false
    var cornerCount = 0
    for (i in simplified.indices) {
        val prev = simplified[(i - 1 + simplified.size) % simplified.size]
        val curr = simplified[i]
        val next = simplified[(i + 1) % simplified.size]
        val v1x = curr.x - prev.x; val v1y = curr.y - prev.y
        val v2x = next.x - curr.x; val v2y = next.y - curr.y
        val dot = v1x * v2x + v1y * v2y
        val mag = sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y)
        if (mag == 0f) continue
        // Require a genuinely sharp corner (angle > ~73°); smooth ellipse curves stay below this.
        if ((dot / mag).coerceIn(-1f, 1f) < 0.3f) cornerCount++
    }
    return cornerCount in 3..6
}

// Returns true if the drawn path is approximately closed (end ≈ start relative to bounding-box size).
internal fun isApproxClosed(points: List<Offset>): Boolean {
    if (points.size < 20) return false
    val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
    val bboxDiag = sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY))
    if (bboxDiag < 50f) return false
    val dx = points.last().x - points.first().x
    val dy = points.last().y - points.first().y
    return sqrt(dx * dx + dy * dy) < bboxDiag * 0.30f
}

internal fun isApproxStraightLine(points: List<Offset>): Boolean {
    if (points.size < 2) return false
    val first = points.first(); val last = points.last()
    val dx = last.x - first.x; val dy = last.y - first.y
    val lineLen = sqrt(dx * dx + dy * dy)
    if (lineLen < 40f) return false
    val lenSq = lineLen * lineLen
    var maxDev = 0f
    for (pt in points) {
        val ex = pt.x - first.x; val ey = pt.y - first.y
        val dist = if (lenSq == 0f) sqrt(ex * ex + ey * ey)
        else {
            val t = (ex * dx + ey * dy) / lenSq
            val cx = first.x + t * dx; val cy = first.y + t * dy
            sqrt((pt.x - cx) * (pt.x - cx) + (pt.y - cy) * (pt.y - cy))
        }
        if (dist > maxDev) maxDev = dist
    }
    return maxDev < lineLen * 0.05f
}

// Snaps `end` to horizontal or vertical relative to `start` if within 2° of those axes.
internal fun snapToCardinal(start: Offset, end: Offset): Offset {
    val dx = end.x - start.x; val dy = end.y - start.y
    val ax = kotlin.math.abs(dx); val ay = kotlin.math.abs(dy)
    return when {
        ay > 0f && ax / ay > 28.636f -> Offset(end.x, start.y)
        ax > 0f && ay / ax > 28.636f -> Offset(start.x, end.y)
        else -> end
    }
}

// Ray-casting point-in-polygon test (all in the same coordinate space).
internal fun pointInPolygon(pt: Offset, poly: List<Offset>): Boolean {
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val xi = poly[i].x; val yi = poly[i].y
        val xj = poly[j].x; val yj = poly[j].y
        if ((yi > pt.y) != (yj > pt.y) && pt.x < (xj - xi) * (pt.y - yi) / (yj - yi) + xi)
            inside = !inside
        j = i
    }
    return inside
}

// Fraction of a stroke's points (un-normalized to screen pixels) that are inside the polygon.
internal fun fractionInsidePolygon(stroke: InkStroke, polyPx: List<Offset>, pageSize: IntSize): Float {
    if (stroke.points.isEmpty()) return 0f
    val pts = stroke.points.map { Offset(it.x * pageSize.width, it.y * pageSize.height) }
    return pts.count { pointInPolygon(it, polyPx) }.toFloat() / pts.size
}

// Tight bounding box of all stroke points in screen-pixel space, with padding.
internal fun computeSelectionBounds(strokes: List<InkStroke>, pageSize: IntSize): Rect {
    val allPts = strokes.flatMap { it.points }
    val pad = 12f
    return Rect(
        left   = allPts.minOf { it.x } * pageSize.width  - pad,
        top    = allPts.minOf { it.y } * pageSize.height - pad,
        right  = allPts.maxOf { it.x } * pageSize.width  + pad,
        bottom = allPts.maxOf { it.y } * pageSize.height + pad
    )
}
