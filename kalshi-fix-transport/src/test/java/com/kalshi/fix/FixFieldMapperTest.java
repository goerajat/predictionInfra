package com.kalshi.fix;

import com.kalshi.client.model.CreateOrderRequest;
import com.kalshi.client.model.OrderAction;
import com.kalshi.client.model.OrderSide;
import com.kalshi.client.model.TimeInForce;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FixFieldMapperTest {

    // ==================== Side Mapping Tests ====================

    @Test
    void testSideMapping_buyYes_shouldBeFIXBuy() {
        assertEquals('1', FixFieldMapper.mapSideToFix("buy", "yes"));
    }

    @Test
    void testSideMapping_sellYes_shouldBeFIXSell() {
        assertEquals('2', FixFieldMapper.mapSideToFix("sell", "yes"));
    }

    @Test
    void testSideMapping_buyNo_shouldBeFIXSell() {
        // Buying no = selling yes in Kalshi's FIX model
        assertEquals('2', FixFieldMapper.mapSideToFix("buy", "no"));
    }

    @Test
    void testSideMapping_sellNo_shouldBeFIXBuy() {
        // Selling no = buying yes in Kalshi's FIX model
        assertEquals('1', FixFieldMapper.mapSideToFix("sell", "no"));
    }

    @Test
    void testSideMapping_caseInsensitive() {
        assertEquals('1', FixFieldMapper.mapSideToFix("BUY", "YES"));
        assertEquals('2', FixFieldMapper.mapSideToFix("Sell", "Yes"));
        assertEquals('2', FixFieldMapper.mapSideToFix("Buy", "No"));
        assertEquals('1', FixFieldMapper.mapSideToFix("SELL", "NO"));
    }

    // ==================== Reverse Side Mapping Tests ====================

    @Test
    void testFixSideToAction_buy() {
        assertEquals("buy", FixFieldMapper.mapFixSideToAction('1'));
    }

    @Test
    void testFixSideToAction_sell() {
        assertEquals("sell", FixFieldMapper.mapFixSideToAction('2'));
    }

    @Test
    void testFixSideToSide_alwaysYes() {
        // Kalshi FIX always deals in "yes" contracts
        assertEquals("yes", FixFieldMapper.mapFixSideToSide('1'));
        assertEquals("yes", FixFieldMapper.mapFixSideToSide('2'));
    }

    // ==================== Price Mapping Tests ====================

    @Test
    void testPriceMapping_buyYes_usesYesPrice() {
        CreateOrderRequest req = CreateOrderRequest.builder()
                .ticker("TEST")
                .side(OrderSide.YES)
                .action(OrderAction.BUY)
                .count(1)
                .yesPrice(65)
                .build();
        assertEquals(65, FixFieldMapper.mapPriceToFix(req));
    }

    @Test
    void testPriceMapping_sellYes_usesYesPrice() {
        CreateOrderRequest req = CreateOrderRequest.builder()
                .ticker("TEST")
                .side(OrderSide.YES)
                .action(OrderAction.SELL)
                .count(1)
                .yesPrice(40)
                .build();
        assertEquals(40, FixFieldMapper.mapPriceToFix(req));
    }

    @Test
    void testPriceMapping_buyNo_convertsToYesComplement() {
        // Buy No at 30 = Sell Yes at 70 → FIX price = 70
        CreateOrderRequest req = CreateOrderRequest.builder()
                .ticker("TEST")
                .side(OrderSide.NO)
                .action(OrderAction.BUY)
                .count(1)
                .noPrice(30)
                .build();
        assertEquals(70, FixFieldMapper.mapPriceToFix(req));
    }

    @Test
    void testPriceMapping_sellNo_convertsToYesComplement() {
        // Sell No at 45 = Buy Yes at 55 → FIX price = 55
        CreateOrderRequest req = CreateOrderRequest.builder()
                .ticker("TEST")
                .side(OrderSide.NO)
                .action(OrderAction.SELL)
                .count(1)
                .noPrice(45)
                .build();
        assertEquals(55, FixFieldMapper.mapPriceToFix(req));
    }

    // ==================== Status Mapping Tests ====================

    @Test
    void testStatusMapping_new_resting() {
        assertEquals("resting", FixFieldMapper.mapFixStatusToKalshi('0'));
    }

    @Test
    void testStatusMapping_partialFill_resting() {
        assertEquals("resting", FixFieldMapper.mapFixStatusToKalshi('1'));
    }

    @Test
    void testStatusMapping_pendingNew_resting() {
        assertEquals("resting", FixFieldMapper.mapFixStatusToKalshi('A'));
    }

    @Test
    void testStatusMapping_replaced_resting() {
        assertEquals("resting", FixFieldMapper.mapFixStatusToKalshi('5'));
    }

    @Test
    void testStatusMapping_filled_executed() {
        assertEquals("executed", FixFieldMapper.mapFixStatusToKalshi('2'));
    }

    @Test
    void testStatusMapping_canceled() {
        assertEquals("canceled", FixFieldMapper.mapFixStatusToKalshi('4'));
    }

    @Test
    void testStatusMapping_pendingCancel_canceled() {
        assertEquals("canceled", FixFieldMapper.mapFixStatusToKalshi('6'));
    }

    @Test
    void testStatusMapping_rejected() {
        assertEquals("rejected", FixFieldMapper.mapFixStatusToKalshi('8'));
    }

    @Test
    void testStatusMapping_expired() {
        assertEquals("expired", FixFieldMapper.mapFixStatusToKalshi('C'));
    }

    // ==================== TimeInForce Mapping Tests ====================

    @Test
    void testTimeInForceMapping_gtc() {
        assertEquals('1', FixFieldMapper.mapTimeInForceToFix("good_till_canceled"));
        assertEquals('1', FixFieldMapper.mapTimeInForceToFix("gtc"));
    }

    @Test
    void testTimeInForceMapping_ioc() {
        assertEquals('3', FixFieldMapper.mapTimeInForceToFix("immediate_or_cancel"));
        assertEquals('3', FixFieldMapper.mapTimeInForceToFix("ioc"));
    }

    @Test
    void testTimeInForceMapping_fok() {
        assertEquals('4', FixFieldMapper.mapTimeInForceToFix("fill_or_kill"));
        assertEquals('4', FixFieldMapper.mapTimeInForceToFix("fok"));
    }

    @Test
    void testTimeInForceMapping_day() {
        assertEquals('0', FixFieldMapper.mapTimeInForceToFix("day"));
    }

    @Test
    void testTimeInForceMapping_null_defaultsGtc() {
        assertEquals('1', FixFieldMapper.mapTimeInForceToFix(null));
    }

    @Test
    void testTimeInForceMapping_reverse() {
        assertEquals("good_till_canceled", FixFieldMapper.mapFixTimeInForceToKalshi('1'));
        assertEquals("immediate_or_cancel", FixFieldMapper.mapFixTimeInForceToKalshi('3'));
        assertEquals("fill_or_kill", FixFieldMapper.mapFixTimeInForceToKalshi('4'));
        assertEquals("day", FixFieldMapper.mapFixTimeInForceToKalshi('0'));
    }

    // ==================== ClOrdId Generation ====================

    @Test
    void testGenerateClOrdId_unique() {
        String id1 = FixFieldMapper.generateClOrdId();
        String id2 = FixFieldMapper.generateClOrdId();
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
        assertTrue(id1.length() <= 64); // Kalshi max ClOrdID length
    }
}
