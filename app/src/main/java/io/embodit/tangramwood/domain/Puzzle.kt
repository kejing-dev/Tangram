package io.embodit.tangramwood.domain

/** One of the three groups of the library (design content.groups). */
data class Group(val id: String, val zh: String, val en: String, val tipZh: String, val tipEn: String)

/** One slot of a figure: a piece kind and its outline in u (design content.figuresExact). */
data class Slot(val kind: Kind, val verts: List<V2>) {
    val centroid: V2 = Poly.centroid(verts)

    /** The pose that lays this kind exactly on the slot (design: reverse rot8 / flipped from the slot's vertices). */
    val pose: Pose = run {
        for (flip in if (kind.canFlip) listOf(false, true) else listOf(false)) for (r in 0 until 8) {
            val p = Pose(centroid, r, flip)
            if (Poly.same(p.outline(kind), verts)) return@run p
        }
        error("slot $kind $verts matches no pose")
    }
}

/** A puzzle (or free play when [slotVerts] is empty). */
data class Figure(
    val id: String,
    val group: String,
    val order: Int,
    val difficulty: Int,
    val nameZh: String,
    val nameEn: String,
    val sloganZh: String,
    val sloganEn: String,
    val color: Long,
    val slotVerts: List<Slot>,
) {
    val slots: List<Slot> get() = slotVerts
    val isFree: Boolean get() = slotVerts.isEmpty()

    /** The figure's bounding-box centre: it sits on the board's centre. */
    val centre: V2 get() = if (isFree) V2.ZERO else Poly.bounds(slotVerts.flatMap { it.verts }).let { (a, b) -> V2((a.x + b.x) / 2, (a.y + b.y) / 2) }

    fun name(zh: Boolean) = if (zh) nameZh else nameEn
    fun slogan(zh: Boolean) = if (zh) sloganZh else sloganEn

    /** Replay / help order (design content.replayOrder): L, L, M, Sq, P, S, S; two of a kind left to right. */
    val replayOrder: List<Int> get() {
        val rank = listOf(Kind.L, Kind.M, Kind.SQ, Kind.P, Kind.S)
        return slots.indices.sortedWith(compareBy({ rank.indexOf(slots[it].kind) }, { slots[it].centroid.x }))
    }

    companion object {
        /** 「随便拼」: a figure with no slots (design content.freePlay). */
        val FREE = Figure("free", "free", 0, 0, "随便拼", "Free play", "", "", 0xFF8E44ADL, emptyList())
    }
}

/** The seven pieces: index -> kind (two big, one middle, two small, square, parallelogram). */
object Pieces {
    val KINDS: List<Kind> = listOf(Kind.L, Kind.L, Kind.M, Kind.S, Kind.S, Kind.SQ, Kind.P)
    const val COUNT = 7
    fun kind(i: Int) = KINDS[i]
    fun nameRes(kind: Kind): String = kind.label
}
