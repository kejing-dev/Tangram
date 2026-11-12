package com.kejing.tangramwood.domain

enum class Language { SYSTEM, ZH, EN }

/** Best result of one figure (design dataModel.records); a hint or a help never counts as a best. */
data class Record(val solved: Boolean = false, val bestMs: Long? = null, val bestMoves: Int? = null)

/** App settings (design dataModel.settings) + per-figure records. */
data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    /** Volume stop 0..2 (low / mid / high). */
    val volume: Int = 2,
    val reducedMotion: Boolean = false,
    val firstRunDone: Boolean = false,
    val records: Map<String, Record> = emptyMap(),
) {
    fun record(id: String): Record = records[id] ?: Record()
}

/** Where a piece is. */
enum class Where { TRAY, BOARD }

/**
 * One piece. In the tray it sits at its home (pose = the home turn, or a preview turn while selected); on the board
 * [pose] is its centroid in puzzle u (figure frame) and its turn / face; [slot] = the slot it snapped into.
 */
data class PieceState(val where: Where, val pose: Pose, val slot: Int? = null) {
    val onBoard: Boolean get() = where == Where.BOARD
    val snapped: Boolean get() = slot != null
}

/** One undo step: the pieces and the move count before a drop (design gameSpec.moveCount, ≤ 50 kept). */
data class Snapshot(val pieces: List<PieceState>, val moves: Int)

/** What a game looks like on disk (design dataModel.save). */
data class SavedGame(
    val figureId: String,
    val pieces: List<PieceState>,
    val moves: Int,
    val elapsedMs: Long,
    val usedHint: Boolean,
    val helps: Int,
    val solved: Boolean,
    val undo: List<Snapshot>,
)

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/** `tw1|figure|pieces|moves|elapsed|hint|helps|solved|undo` with pieces `W,x,y,r,f,s;…` and undo `moves=pieces/…`. */
object SaveCodec {
    private fun piece(p: PieceState) = listOf(
        if (p.onBoard) "B" else "T", "%.4f".format(java.util.Locale.US, p.pose.centre.x), "%.4f".format(java.util.Locale.US, p.pose.centre.y),
        Math.floorMod(p.pose.rot8, 8).toString(), if (p.pose.flipped) "1" else "0", p.slot?.toString() ?: "-",
    ).joinToString(",")

    private fun pieces(list: List<PieceState>) = list.joinToString(";") { piece(it) }

    private fun parsePieces(s: String): List<PieceState> = s.split(";").map { e ->
        val f = e.split(",")
        require(f.size == 6)
        PieceState(if (f[0] == "B") Where.BOARD else Where.TRAY, Pose(V2(f[1].toFloat(), f[2].toFloat()), f[3].toInt(), f[4] == "1"), f[5].toIntOrNull())
    }.also { require(it.size == Pieces.COUNT) }

    fun encode(g: SavedGame): String = listOf(
        "tw1", g.figureId, pieces(g.pieces), g.moves.toString(), g.elapsedMs.toString(), if (g.usedHint) "1" else "0",
        g.helps.toString(), if (g.solved) "1" else "0", g.undo.joinToString("/") { "${it.moves}=${pieces(it.pieces)}" },
    ).joinToString("|")

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size != 9 || f[0] != "tw1") return null
        val fig = if (f[1] == Figure.FREE.id) Figure.FREE else Figures.byId(f[1]) ?: return null
        val ps = parsePieces(f[2])
        ps.forEach { p -> p.slot?.let { require(it in fig.slots.indices) } }
        val undo = if (f[8].isBlank()) emptyList() else f[8].split("/").map { u ->
            val (m, rest) = u.split("=", limit = 2)
            Snapshot(parsePieces(rest), m.toInt())
        }
        SavedGame(fig.id, ps, f[3].toInt(), f[4].toLong(), f[5] == "1", f[6].toInt(), f[7] == "1", undo)
    }.getOrNull()
}

/** Settings records codec: `id=solved,bestMs,bestMoves;…`. */
object RecordsCodec {
    fun encode(r: Map<String, Record>) = r.entries.joinToString(";") { (k, v) -> "$k=${if (v.solved) 1 else 0},${v.bestMs ?: ""},${v.bestMoves ?: ""}" }
    fun decode(s: String): Map<String, Record> = s.split(";").filter { "=" in it }.mapNotNull { e ->
        runCatching {
            val (k, v) = e.split("=", limit = 2)
            val f = v.split(",")
            k to Record(f[0] == "1", f.getOrNull(1)?.toLongOrNull(), f.getOrNull(2)?.toIntOrNull())
        }.getOrNull()
    }.toMap()
}
