package tracker.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyFormatTest {
    @Test fun `indian grouping`() {
        assertEquals("0.00", Paise(0).formatInr())
        assertEquals("999.05", Paise(99_905).formatInr())
        assertEquals("1,000.00", Paise.rupees(1_000).formatInr())
        assertEquals("1,25,000.50", Paise(12_500_050).formatInr())
        assertEquals("12,34,56,789.00", Paise.rupees(123_456_789).formatInr())
        assertEquals("-2,000.00", Paise.rupees(-2_000).formatInr())
    }
}
