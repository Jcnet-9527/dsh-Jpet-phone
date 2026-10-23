package io.github.dshpet.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The balance bubble. `balance_infos` is an **array** of currency rows; reading
 * it as an object is what made every query end with "余额查询成功，但接口没给出数字",
 * so the shape and the formatting are both pinned here.
 *
 * Only the formatting is exercised: the JVM unit tests run against the mockable
 * android.jar, whose `org.json` is a stub that answers every call with null, so
 * parsing is verified on the device instead of faked here.
 */
class BalanceFormatTest {
    private fun row(currency: String, total: Double, granted: Double = 0.0,
                    toppedUp: Double = 0.0) = BalanceRow(currency, total, granted, toppedUp)

    @Test
    fun theOfficialPayloadShowsTotalAndTheSplit() {
        assertEquals("余额 ¥110.00（赠金 ¥10.00 · 充值 ¥100.00）",
            ChatClient.renderBalance(listOf(row("CNY", 110.00, 10.00, 100.00))))
    }

    @Test
    fun aToppedUpOnlyAccountHasNoGrantedPart() {
        assertEquals("余额 ¥20.50（充值 ¥20.50）",
            ChatClient.renderBalance(listOf(row("CNY", 20.50, 0.0, 20.50))))
    }

    @Test
    fun aGrantedOnlyAccountHasNoToppedUpPart() {
        assertEquals("余额 ¥10.00（赠金 ¥10.00）",
            ChatClient.renderBalance(listOf(row("CNY", 10.00, 10.00, 0.0))))
    }

    @Test
    fun anEmptyAccountStillReportsItsNumber() {
        assertEquals("余额 ¥0.00", ChatClient.renderBalance(listOf(row("CNY", 0.0))))
    }

    @Test
    fun dollarsUseTheirOwnSign() {
        assertEquals("余额 $5.00（充值 $5.00）",
            ChatClient.renderBalance(listOf(row("USD", 5.00, 0.0, 5.00))))
    }

    @Test
    fun anUnknownCurrencyKeepsItsCode() {
        assertEquals("余额 HKD 8.00", ChatClient.renderBalance(listOf(row("HKD", 8.00))))
    }

    @Test
    fun twoCurrenciesAreListedWithoutASplit() {
        assertEquals("余额 ¥10.00 · $2.00",
            ChatClient.renderBalance(listOf(row("CNY", 10.00), row("USD", 2.00))))
    }

    @Test
    fun noRowsKeepsTheOldFallback() {
        assertEquals("余额查询成功，但接口没给出数字", ChatClient.renderBalance(emptyList()))
    }

    @Test
    fun versionNumbersCompareAsNumbersNotStrings() {
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.1"))
        assertTrue(UpdateChecker.isNewer("0.6.3", "0.6.2"))
        assertFalse(UpdateChecker.isNewer("0.6.2", "0.6.2"))
        assertFalse(UpdateChecker.isNewer("0.6.1", "0.6.2"))
    }
}
