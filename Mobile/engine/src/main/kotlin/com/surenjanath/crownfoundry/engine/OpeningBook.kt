package com.surenjanath.crownfoundry.engine

/**
 * The same 16 English-draughts lines as `Backend/ai/opening_book.py`.
 * CFE1 does not carry this — the phone needs its own copy so offline play uses theory too.
 */
object OpeningBook {

    private class Node {
        val children = mutableMapOf<String, Node>()
    }

    private val lines = listOf(
        listOf("11-15", "23-19", "8-11", "22-17", "4-8", "17-13", "15-18", "24-20", "9-14", "26-23", "11-15"),
        listOf("11-15", "22-18", "15x22", "25x18", "8-11", "29-25", "4-8", "24-20", "10-15", "25-22", "12-16"),
        listOf("11-15", "23-18", "8-11", "27-23", "4-8", "23-19", "9-14", "18x9", "5x14", "26-23", "1-5"),
        listOf("11-15", "23-19", "9-14", "27-23", "8-11", "22-17", "4-8", "24-20", "14-18", "23x14", "10x17"),
        listOf("11-15", "23-19", "8-11", "22-17", "11-16", "24-20", "16x23", "27x11", "7x16", "20x11", "3x8"),
        listOf("11-15", "23-19", "8-11", "22-17", "9-13", "17-14", "10x17", "21x14", "15-18", "26-22", "13-17"),
        listOf("11-15", "23-19", "9-14", "22-17", "6-9", "17-13", "2-6", "26-22", "8-11", "22-18", "15x22"),
        listOf("11-16", "24-20", "16-19", "23x16", "12x19", "22-18", "9-14", "18x9", "5x14", "25-22", "10-15"),
        listOf("9-13", "22-18", "11-15", "18x11", "8x15", "24-20", "4-8", "28-24", "8-11", "23-19", "15-18"),
        listOf("11-15", "21-17", "9-13", "25-21", "8-11", "30-25", "4-8", "24-19", "15x24", "28x19", "13-18"),
        listOf("9-14", "22-17", "11-15", "25-22", "8-11", "17-13", "4-8", "29-25", "15-18", "22x15", "11x18"),
        listOf("11-15", "23-19", "8-11", "22-17", "3-8", "25-22", "11-16", "24-20", "15x24", "28x19", "9-14"),
        listOf("10-15", "22-18", "15x22", "26x17", "11-15", "24-19", "15x24", "28x19", "8-11", "25-22", "4-8"),
        listOf("11-15", "24-20", "8-11", "28-24", "4-8", "23-19", "15x24", "20x27", "9-14", "22-18", "14x23"),
        listOf("10-14", "22-18", "11-15", "18x11", "8x15", "24-19", "15x24", "28x19", "7-11", "25-22", "4-8"),
        listOf("11-15", "24-19", "15x24", "28x19", "8-11", "22-18", "10-14", "18x9", "5x14", "25-22", "7-10"),
    )

    private val root = Node().also { tree ->
        for (line in lines) {
            var curr = tree
            for (move in line) {
                curr = curr.children.getOrPut(normalize(move)) { Node() }
            }
        }
    }

    private fun normalize(move: String): String =
        move.replace('x', '-').replace('X', '-').replace(':', '-').trim()

    fun lookup(history: List<String>, board: Board): String? {
        var curr = root
        for (move in history) {
            curr = curr.children[normalize(move)] ?: return null
        }
        if (curr.children.isEmpty()) return null
        val legal = board.legalMoves().associateBy { normalize(it.notation()) }
        val hits = curr.children.keys.mapNotNull { legal[it]?.notation() }
        return hits.randomOrNull()
    }
}
