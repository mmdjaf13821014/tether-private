package app.tether.client

import app.tether.client.ui.barPadding
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Window padding for the system bars and the keyboard (#12). */
class BarPaddingTest {
    @Test fun barsOnly() = assertArrayEquals(intArrayOf(0, 80, 0, 48), barPadding(intArrayOf(0, 80, 0, 48), 0))

    /** The keyboard covers the navigation bar: the larger one counts, they do not add up. */
    @Test fun keyboardReplacesTheNavigationBar() =
        assertArrayEquals(intArrayOf(0, 80, 0, 900), barPadding(intArrayOf(0, 80, 0, 48), 900))

    @Test fun cutoutSidesKept() = assertArrayEquals(intArrayOf(96, 0, 0, 48), barPadding(intArrayOf(96, 0, 0, 48), 20))
}
