package io.github.davifmn.ledger.settlement;

/** A trade message that can never be settled, whatever the retries: it goes to the dead-letter topic. */
public class InvalidTradeException extends RuntimeException {

    public InvalidTradeException(String message) {
        super(message);
    }
}
