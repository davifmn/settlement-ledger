package io.github.davifmn.ledger.sepa;

import io.github.davifmn.ledger.common.ApiException;
import org.springframework.http.HttpStatus;

/** The message is not well-formed XML, or not the expected ISO 20022 message. */
public class XmlFormatException extends ApiException {

    public XmlFormatException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }
}
