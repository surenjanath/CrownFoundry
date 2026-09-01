package com.surenjanath.crownfoundry.enums

/**
 * How the 8x8 grid is coloured.
 *
 * The board used to be drawn out of [com.surenjanath.crownfoundry.ui.styling.ColorPalette]'s
 * `background1` and `background2` - the same two tokens that separate a card from the page behind
 * it. Those are deliberately *close*: a surface that shouted at its background would be a bad
 * surface. A draughts board has the opposite requirement, and the two uses cannot be served by one
 * pair of colours. In the Pure Black palette the two tokens are the same colour, so the board had
 * no pattern at all.
 *
 * Every style here therefore carries its own squares, chosen so the light and dark ones are far
 * enough apart to read as a board across the room, and so both stay legible under the orange
 * accent the human's pieces are drawn in.
 */
enum class BoardStyle(val label: String, val description: String) {
    Classic(
        label = "Classic",
        description = "Buff and green, the way a tournament draughts board is printed."
    ),
    Wood(
        label = "Wood",
        description = "Warm maple and walnut, like a board you would keep on a shelf."
    ),
    Slate(
        label = "Slate",
        description = "Cool greys. The quietest of the four, and the easiest on a bright screen."
    ),
    Accent(
        label = "Accent",
        description = "Tinted to whatever accent colour you picked, with the contrast held wide " +
                "enough to still read as a board."
    )
}
