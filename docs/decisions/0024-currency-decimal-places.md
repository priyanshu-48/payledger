# ADR 0024: Decimal places follow the currency
- Status: accepted · Date: 2026-11-09
## Context
Money is a `long` in minor units plus a currency code (SPEC section 5). `Money.toMajor()` and `Money.fromMajor()` convert at the API boundary, and both hard-coded two decimal places. That is right for INR and USD but wrong for JPY (none) and KWD (three): 500 yen would have been read as 5.00 yen. Every conversion goes through those two methods (transfer, payment and account responses, notification text), so nothing else assumed two places.
## Decision
- `Money.fractionDigits(currency)` returns the ISO 4217 number of decimal places from `java.util.Currency` (2 for INR/USD, 0 for JPY, 3 for KWD). `toMajor` and `fromMajor` use it.
- `fromMajor` still rejects extra decimals instead of rounding (`RoundingMode.UNNECESSARY`); an unknown code (for example `ZZZ`) or a pseudo-currency with no minor unit (`XXX`) throws `IllegalArgumentException`.
- The two controllers map both failures to the existing client error (400): the message no longer says "2 decimal places".
- The `Money` constructor and the database CHECK still accept any three upper-case letters: stored data and events are not re-validated, and the conversion is the only place that needs the decimal count.
## Consequences
- INR and USD behave exactly as before; JPY and KWD now work through the API (tests in `MoneyTest` and `TransferApiTest`).
- An existing account or payment with a code the JVM does not know would now fail when its amount is rendered (500) instead of being shown with two places. No such data exists in this project; accepting only ISO codes at account creation would be the next step.
- Java's `Currency` data follows the JDK version (it can change when ISO 4217 changes), so the decimal count is not stored with the account.
- Rejected: storing the decimal count per account (more schema for no current need); a hand-written table of exceptions (the JDK already has one).
