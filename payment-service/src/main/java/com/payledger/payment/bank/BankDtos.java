package com.payledger.payment.bank;

/** Wire format between BankClient and the mock bank. */
public final class BankDtos {

    private BankDtos() {
    }

    public record AuthorizeRequest(String reference, long amountMinor, String currency) {
    }

    /** status: APPROVED or DECLINED; for capture/refund calls CAPTURED or REFUNDED. */
    public record AuthResponse(String reference, String bankRef, String status, String reason) {
    }
}
